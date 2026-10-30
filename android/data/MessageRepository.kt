package chat.client.data

import android.content.Context
import android.util.Log
import chat.client.domain.MessageCodec
import chat.client.domain.MessageRef
import chat.client.domain.SystemFormat
import chat.client.domain.TransferCodec
import chat.client.domain.Validators
import chat.client.domain.wrap
import chat.client.net.RelayException
import chat.crypto.MessageRecord
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MessageRepo"

// Транспорт и журнал: дрейн очереди, отправка с TOFU-защитой, реакции,
// удаление и пересылка. Единая точка записи в MessageStore (через StoreGate).
//
// Надёжность дрейна (A14): каждое вычерпнутое сообщение сначала пишется
// в write-ahead журнал (блобы уже зашифрованы нашим ключом), обрабатывается
// и только потом удаляется из журнала. Падение процесса между ответом
// сервера и записью в историю не теряет сообщение — оно доигрывается
// при следующем дрейне.
class MessageRepository(private val container: AppContainer) {

    // TTL-кэш ключей собеседников: без блокировки вокруг сети (A14),
    // ConcurrentHashMap + TTL на запись. Пустой список НЕ кэшируем (A7):
    // только что зарегистрированный собеседник не застревает на 404
    private class CachedKeys(val devices: List<Pair<String, ByteArray>>, val fetchedAt: Long)

    private val keyCache = ConcurrentHashMap<String, CachedKeys>()

    private fun peerDevices(session: SessionInfo, peer: String): List<Pair<String, ByteArray>> {
        val now = System.currentTimeMillis()
        val cached = keyCache[peer]
        if (cached != null && now - cached.fetchedAt < KEY_TTL_MS) return cached.devices
        val devices = session.api.peerKeys(peer)
        if (devices.isNotEmpty()) keyCache[peer] = CachedKeys(devices, now)
        return devices
    }

    // сброс кэша ключей пира (или всех): вызывается при accept новых
    // ключей и при KeyChanged, иначе отправка уйдёт по устаревшему составу
    fun invalidatePeerCache(peer: String? = null) {
        if (peer == null) keyCache.clear() else keyCache.remove(peer)
    }

    // TOFU: сверяем текущий состав с запомненным при КАЖДОЙ отправке;
    // смена ключа/новое устройство — KeyChangedException, отправка блокируется
    @Throws(KeyChangedException::class)
    private fun guard(session: SessionInfo, peer: String, devices: List<Pair<String, ByteArray>>) {
        if (devices.isEmpty()) return
        when (val verdict = container.knownKeys.verdict(peer, devices)) {
            is chat.client.domain.Tofu.Verdict.FirstSeen ->
                container.knownKeys.accept(peer, devices) // первый контакт: молча запоминаем
            is chat.client.domain.Tofu.Verdict.Ok -> {}
            is chat.client.domain.Tofu.Verdict.Changed ->
                throw KeyChangedException(peer, verdict.devices, isNewDevice = false)
            is chat.client.domain.Tofu.Verdict.NewDevice ->
                throw KeyChangedException(peer, verdict.devices, isNewDevice = true)
        }
    }

    // ─── отправка ───

    class GroupSendReport(
        val failedMembers: List<String>, // перманентные (404)
        val retryMembers: List<String>, // временный сбой — повтор позже
        val syncPending: Boolean = false, // A2: копия на свои устройства не дошла
    )

    fun sendPayload(
        session: SessionInfo,
        chatId: String,
        payload: ByteArray,
        members: List<String>? = null,
    ): GroupSendReport {
        val group = container.groups.byId(chatId)
        return if (group != null) {
            sendGroup(session, group, payload, members)
        } else {
            sendPeer(session, chatId, payload)
            GroupSendReport(emptyList(), emptyList())
        }
    }

    private fun sendPeer(session: SessionInfo, peer: String, payload: ByteArray) {
        val devices = peerDevices(session, peer)
        if (devices.isEmpty()) throw RelayException(404, "$peer не зарегистрирован")
        // «Избранное»: свои устройства сверены при линковке — TOFU не нужен;
        // блоб себе не шлём, локальная копия пишется из outbox
        if (peer == session.login) {
            val others = devices.filter { it.first != session.deviceId }
            if (others.isNotEmpty()) {
                session.api.send(session.token, peer, "chat", session.sealFor(others, payload))
            }
            return
        }
        guard(session, peer, devices)
        session.api.send(session.token, peer, "chat", session.sealFor(devices, payload))
        // копия на свои другие устройства (C4): сбой синка — сбой отправки,
        // outbox доиграет обе доставки (пир отбросит дубль по dedup)
        syncToSelf(session, peer, payload)
    }

    // TYPE_SYNC (C4): {chatId, контент} на свои устройства, кроме текущего.
    // Свои устройства сверены при линковке — TOFU-гард не нужен.
    // А1: контент сверх потолка не отправляем вовсе — приёмная сторона
    // его отбросит, а блоб будет гонять очередь зря
    private fun syncToSelf(session: SessionInfo, chatId: String, content: ByteArray) {
        val mine = peerDevices(session, session.login).filter { it.first != session.deviceId }
        if (mine.isEmpty()) return
        if (content.size > chat.client.domain.SyncFormat.MAX_CONTENT) {
            Log.w(TAG, "синк $chatId пропущен: контент ${content.size} выше потолка")
            return
        }
        val sync = chat.client.domain.SyncFormat.encode(chatId, content)
        session.api.send(session.token, session.login, "chat", session.sealFor(mine, sync))
    }

    // Фаза AA8 (аналог draft-синка у Telegram): черновик — на свои другие
    // устройства, в журнал не пишется; сбой некритичен — не критичные данные
    // Фаза AJ: папки — на свои другие устройства; сбой некритичен
    fun syncFolders(session: SessionInfo, folders: List<chat.client.domain.ChatFolder>, rev: Long) {
        val mine = peerDevices(session, session.login).filter { it.first != session.deviceId }
        if (mine.isEmpty()) return
        try {
            val payload = chat.client.domain.FolderSyncFormat.encode(
                rev, folders.take(chat.client.domain.FolderSyncFormat.MAX_FOLDERS),
            )
            session.api.send(session.token, session.login, "chat", session.sealFor(mine, payload))
        } catch (_: Exception) {
        }
    }

    fun syncDraft(session: SessionInfo, chatId: String, text: String, at: Long) {
        val mine = peerDevices(session, session.login).filter { it.first != session.deviceId }
        if (mine.isEmpty()) return
        try {
            val payload = chat.client.domain.DraftFormat.encode(chatId, text.take(chat.client.domain.DraftFormat.MAX_TEXT), at)
            session.api.send(session.token, session.login, "chat", session.sealFor(mine, payload))
        } catch (_: Exception) {
            // следующий save с живой сетью доиграет
        }
    }

    // ─── звонки (Фаза GA): события — напрямую в очередь, без outbox-ретраев ───
    // Задержка критична: живой WS-коннект поднимает дрейн за сотни
    // миллисекунд, а ретраи размазали бы ринг. TOFU-гард как у sendPeer;
    // свои устройства — без гарда (сверены линковкой). false — сеть/404:
    // отправитель сам решает, повторить или отменить звонок
    fun sendCallEvent(session: SessionInfo, to: String, event: chat.client.domain.CallFormat.Event): Boolean {
        val devices = try {
            peerDevices(session, to)
        } catch (_: Exception) {
            return false
        }
        if (devices.isEmpty()) return false
        val targets = if (to == session.login) {
            devices.filter { it.first != session.deviceId }
        } else {
            devices
        }
        if (targets.isEmpty()) return true // своим больше не нужно
        if (to != session.login) guard(session, to, targets)
        return try {
            session.api.send(session.token, to, "chat", session.sealFor(targets, chat.client.domain.CallFormat.encode(event.ev, event.callId, event.ts, event.sdp, event.cands)))
            true
        } catch (e: KeyChangedException) {
            throw e // TOFU-блок — звонок невозможен до решения пользователя
        } catch (e: Exception) {
            Log.w(TAG, "событие звонка ${event.callId}/${event.ev} не ушло к $to", e)
            false
        }
    }

    private fun sendGroup(
        session: SessionInfo,
        group: GroupChat,
        payload: ByteArray,
        members: List<String>?,
    ): GroupSendReport {
        val wrapped = group.wrap(payload)
        return sendGroupEnvelope(session, group.id, wrapped, members)
    }

    // fan-out УЖЕ завёрнутого группового конверта (2.2: batch — один запрос
    // на группу, прежние N запросов упирались в лимит отправки и медленную
    // сеть). Ошибка участника перманентна только при 404; IO/5xx/429 —
    // временная, участник остаётся в повторе (A4). Сменивший ключ участник
    // не блокирует доставку остальным: его блобы не едут, KeyChanged
    // возвращается с частичным прогрессом (A2)
    fun sendGroupEnvelope(
        session: SessionInfo,
        chatId: String,
        wrapped: ByteArray,
        members: List<String>?,
    ): GroupSendReport {
        val group = container.groups.byId(chatId)
        val targets = (members ?: group?.members ?: emptyList())
            .distinct().filter { it != session.login }
        val failed = ArrayList<String>()
        val retry = ArrayList<String>()
        val deliveredSoFar = HashSet<String>()

        // TOFU-проверка и шифрование по участникам ДО отправки (2.2)
        var tofuBlock: KeyChangedException? = null
        val batch = ArrayList<chat.client.net.BatchRecipient>()
        for (member in targets) {
            try {
                val devices = peerDevices(session, member)
                if (devices.isEmpty()) {
                    failed += member
                    continue
                }
                guard(session, member, devices)
                batch += chat.client.net.BatchRecipient(member, session.sealFor(devices, wrapped))
            } catch (e: KeyChangedException) {
                if (tofuBlock == null) tofuBlock = e
            } catch (e: RelayException) {
                // 3.4: авторизация отвергнута — это не «участник недоступен»,
                // пауза всей очереди (authHold) без сжигания попыток
                if (e.code == 401 || e.code == 403) throw e
                if (e.code == 404) failed += member else retry += member
            } catch (e: Exception) {
                Log.w(TAG, "группа $chatId: шифрование для $member отложено", e)
                retry += member
            }
        }
        if (batch.isNotEmpty()) {
            try {
                val errors = session.api.sendBatch(session.token, "chat", batch)
                for ((member, code) in errors) {
                    if (code == 404) failed += member else retry += member
                }
                for (r in batch) {
                    if (r.to !in errors) deliveredSoFar += r.to
                }
            } catch (e: RelayException) {
                if (e.code == 401 || e.code == 403) throw e // 3.4: authHold
                if (e.code == 404) {
                    // старый сервер без batch-роута: по одному, как прежде
                    for (r in batch) {
                        try {
                            session.api.send(session.token, r.to, "chat", r.blobs)
                            deliveredSoFar += r.to
                        } catch (e2: RelayException) {
                            if (e2.code == 401 || e2.code == 403) throw e2
                            if (e2.code == 404) failed += r.to else retry += r.to
                        } catch (e2: Exception) {
                            retry += r.to
                        }
                    }
                } else {
                    Log.w(TAG, "группа $chatId: batch отложен (${e.code})")
                    for (r in batch) retry += r.to
                }
            } catch (e: Exception) {
                Log.w(TAG, "группа $chatId: batch отложен", e)
                for (r in batch) retry += r.to
            }
        }
        // смена ключей — после доставки остальных: частичный прогресс едет
        // с исключением (A2), TOFU-блок не блокирует группу
        tofuBlock?.let {
            throw KeyChangedException(it.peer, it.devices, it.isNewDevice, deliveredSoFar)
        }
        // полный прогон по целям без временных сбоев — копия на свои
        // устройства (C4); при частичном повторе синк уйдёт с последним.
        // A2: сбой синка — НЕ фиктивный участник, а отдельный флаг:
        // запись не завершается, пока копия на свои не ушла
        var syncPending = false
        if (retry.isEmpty()) {
            try {
                syncToSelf(session, chatId, wrapped)
            } catch (e: KeyChangedException) {
                throw e // свои ключи тоже могут смениться
            } catch (e: Exception) {
                syncPending = true
                Log.w(TAG, "синхронизация $chatId на свои устройства отложена", e)
            }
        }
        return GroupSendReport(failed, retry, syncPending)
    }

    // A2: повтор копии на свои устройства для уже доставленной группы;
    // true — синк прошёл
    fun retryGroupSync(session: SessionInfo, chatId: String, wrapped: ByteArray): Boolean = try {
        syncToSelf(session, chatId, wrapped)
        true
    } catch (e: KeyChangedException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "повторная синхронизация $chatId отложена", e)
        false
    }

    // журнал СВОЕЙ записи + посев дедупа для TYPE_SYNC (C4): копия,
    // пришедшая с другого своего устройства, не задублируется
    fun journalOwn(session: SessionInfo, chatId: String, content: ByteArray, tsHint: Long = 0): chat.crypto.MessageRecord {
        val record = session.store.appendMonotonic(chatId, session.login, content, tsHint)
        session.dedup.remember(session.dedupKey(session.login, content))
        return record
    }

    // существование пира (новый чат, создание группы)
    fun peerExists(session: SessionInfo, peer: String): Boolean = try {
        peerDevices(session, peer).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    // 3.1: то же без проглатывания сети — newChat обязан отличать «нет
    // сети» от «не зарегистрирован»; сетевая ошибка валится наружу
    fun peerExistsStrict(session: SessionInfo, peer: String): Boolean =
        peerDevices(session, peer).isNotEmpty()

    // ─── дрейн ───

    class DrainReport(
        val saved: Int,
        val byChat: Map<String, Int>, // новые содержательные сообщения
        val reactions: Set<String>, // чаты с изменениями реакций (без уведомлений)
        val edits: Set<String>, // чаты с применёнными правками (без уведомлений)
        val pins: Set<String>, // чаты с изменёнными закрепами (без уведомлений)
        val deletes: Set<String>, // чаты с «удалёнными у всех» (без уведомлений)
        val avatars: Set<String> = emptySet(), // чаты со сменой аватара (Фаза U1)
        val mentions: Set<String> = emptySet(), // чаты с @упоминанием меня (Фаза V4)
        val votes: Set<String> = emptySet(), // чаты с голосами в опросах (Фаза W1)
        val bios: Set<String> = emptySet(), // чаты со сменой «О себе» (Фаза W2)
        // AL9 (аналог «Reacted to your message» у Telegram): реакции,
        // задевшие МОИ сообщения — по одной заметке на чат+автора
        val reactionNotices: List<chat.client.domain.ReactionNotify.Notice> = emptyList(),
        // Фаза X1: чаты, где ПОСЛЕДНЕЕ новое сообщение беззвучное —
        // уведомление постим на тихий канал, без внутреннего звука
        val silentChats: Map<String, Boolean> = emptyMap(),
        val broken: Int,
        val transfers: Int,
        val newDevices: List<chat.client.domain.DeviceFormat.Decoded>,
        val mediaToFetch: List<Pair<String, chat.client.domain.MediaInfo>>, // качать после уведомлений (Фаза Y3: chatId — для автосейва)
    )

    // индекс дублей transfer строится лениво — один раз на весь дрейн
    private var transferIndex: HashMap<String, HashSet<String>>? = null

    // single-flight на весь дрейн (A1): параллельные вызовы из
    // ConnectionManager и BackgroundSyncWorker не играют WAL дважды
    private val drainMutex = Mutex()

    // карантин приглашений в группы (C4) и сообщений неподтверждённых
    // участников (A3): блобы хранятся ЗАШИФРОВАННЫМИ (исходный блоб,
    // ключи — у нас): plaintext на диске не появляется.
    // Лимиты (A3): ≤50 файлов и ≤1 МБ на группу, старейшие вытесняются

    private fun quarantineDir(context: Context, chatId: String) =
        File(File(context.filesDir, "quarantine"), chatId)

    private fun quarantine(context: Context, chatId: String, msg: WalMessage) {
        try {
            // A4: блоб до 10 МБ не обязан влезать в карантин на 1 МБ —
            // пишем только то, что помещается, остальное честно теряем
            if (msg.blob.size > QUARANTINE_MAX_BYTES) {
                Log.w(TAG, "карантин: блоб ${msg.blob.size} выше лимита — отброшен")
                return
            }
            val dir = quarantineDir(context, chatId)
            dir.mkdirs()
            evictQuarantine(dir)
            val file = File(dir, "${System.currentTimeMillis()}-${msg.blob.hashCode()}.wal")
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeUTF(msg.from)
                out.writeUTF(msg.kind)
                out.writeInt(msg.blob.size)
                out.write(msg.blob)
            }
        } catch (e: Exception) {
            Log.e(TAG, "карантин: запись не удалась", e)
        }
    }

    // вытеснение старейших файлов карантина за лимитами (A3)
    private fun evictQuarantine(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        var totalBytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count < QUARANTINE_MAX_FILES && totalBytes < QUARANTINE_MAX_BYTES) break
            totalBytes -= file.length()
            count--
            file.delete()
        }
    }

    // принять приглашение: группа создаётся с ревизией отправителя,
    // карантин доигрывается (C4)
    suspend fun acceptGroupInvite(context: Context, session: SessionInfo, chatId: String): Map<String, Int> =
        drainMutex.withLock {
            val invite = container.groups.invite(chatId) ?: return@withLock emptyMap()
            container.groups.save(
                GroupChat(
                    chatId, invite.name, invite.members, invite.rev, invite.broadcast,
                    invite.desc, invite.slowMode, invite.noForward,
                    chat.client.domain.GroupRules.sanitizeAdmins(invite.admins, invite.members),
                    invite.noReactions,
                ),
            )
            container.groups.clearInvite(chatId)
            // принимаем приглашение обратно — группа снова жива
            container.groups.clearLeft(chatId)
            replayQuarantineLocked(context, session, chatId)
        }

    // подтвердить новый состав (A9): применяется с ревизией отправителя,
    // карантин сообщений «новичков» доигрывается (A3)
    suspend fun acceptGroupMembers(context: Context, session: SessionInfo, chatId: String): Map<String, Int> =
        drainMutex.withLock {
            container.groups.acceptPendingChange(chatId) ?: return@withLock emptyMap()
            replayQuarantineLocked(context, session, chatId)
        }

    // отклонить: состав не применяется, карантин стирается (C4)
    fun declineGroupInvite(context: Context, chatId: String) {
        container.groups.clearInvite(chatId)
        quarantineDir(context, chatId).deleteRecursively()
    }

    // реплей карантина группы: всё, что теперь проходит валидацию, — в историю.
    // A4: возвращает карту новых сообщений по чатам — вызывающий обязан
    // инвалидировать сводки и уведомить
    private fun replayQuarantineLocked(context: Context, session: SessionInfo, chatId: String): Map<String, Int> {
        val dir = quarantineDir(context, chatId)
        val files = dir.listFiles() ?: return emptyMap()
        val byChat = HashMap<String, Int>()
        val reactions = HashSet<String>()
        val edits = HashSet<String>()
        val pins = HashSet<String>()
        val deletes = HashSet<String>()
        val avatarChats = HashSet<String>()
        val newDevices = ArrayList<chat.client.domain.DeviceFormat.Decoded>()
        val media = ArrayList<Pair<String, chat.client.domain.MediaInfo>>()
        val deliveredTo = HashSet<String>()
        val voteChats = HashSet<String>()
        val bioChats = HashSet<String>()
        val silentChats = HashMap<String, Boolean>()
        for (file in files.sortedBy { it.name }) {
            try {
                val msg = DataInputStream(file.inputStream().buffered()).use { input ->
                    val from = input.readUTF()
                    val kind = input.readUTF()
                    val len = input.readInt()
                    if (len < 0 || len > 12 * 1024 * 1024) throw IllegalStateException("битый карантин")
                    val blob = ByteArray(len)
                    input.readFully(blob)
                    WalMessage("q", from, kind, blob)
                }
                processIncoming(
                    context, session, msg, byChat, reactions, edits, pins, deletes,
                    newDevices, media, deliveredTo, avatarChats,
                    voteChats = voteChats, bioChats = bioChats, silentChats = silentChats,
                )
            } catch (e: Exception) {
                Log.w(TAG, "карантин: битая запись удалена", e)
            } finally {
                file.delete()
            }
        }
        dir.delete()
        return byChat
    }

    // индекс dedup засеян из хвоста истории — по одному разу на СЕССИЮ (A2):
    // после повторного входа в том же процессе индекс холодный и обязан
    // прогреться заново, иначе копии с устройства-родня задублируются
    private val warmedSessions = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<SessionInfo, Boolean>(),
    )

    suspend fun drain(context: Context, session: SessionInfo): DrainReport =
        drainMutex.withLock { drainLocked(context, session) }

    private fun drainLocked(context: Context, session: SessionInfo): DrainReport {
        var saved = 0
        var broken = 0
        var transfers = 0
        val byChat = HashMap<String, Int>()
        val reactionChats = HashSet<String>()
        val editChats = HashSet<String>()
        val pinChats = HashSet<String>()
        val deleteChats = HashSet<String>()
        val avatarChats = HashSet<String>()
        val mentionChats = HashSet<String>()
        val voteChats = HashSet<String>()
        val bioChats = HashSet<String>()
        val fxChats = HashSet<String>() // GH4: эффект отправки — не в журнал, а в UI
        val newDevices = ArrayList<chat.client.domain.DeviceFormat.Decoded>()
        val mediaToFetch = ArrayList<Pair<String, chat.client.domain.MediaInfo>>()
        val deliveredTo = HashSet<String>()
        val silentChats = HashMap<String, Boolean>()
        // AL9 (аналог «Reacted to your message» у Telegram): сырые заметки
        // о реакциях — цель ещё не проверена; разбор после очереди
        val rawReactions = ArrayList<chat.client.domain.ReactionNotify.Notice>()
        // Фаза GA: отложенные «пропущенные звонки» — callId → пир; запись
        // пишется после полной обработки очереди, чтобы синхронная копия
        // записи с устройства, ответившего на звонок (TYPE_SYNC из той же
        // очереди), успела защитить от ложной «пропущенности»
        val missedCalls = HashMap<String, String>()
        transferIndex = null // индекс дублей строится лениво, раз на дрейн
        if (warmedSessions.add(session)) {
            warmDedupFromHistory(session)
        }

        // 1) доигрываем WAL прошлого прогона (краш до записи в историю);
        //    файл удаляется только ПОСЛЕ обработки записи — падение
        //    процесса в середине проигрывания не теряет блобы
        for (msg in walLoad(context)) {
            val r = processIncoming(context, session, msg, byChat, reactionChats, editChats, pinChats, deleteChats, newDevices, mediaToFetch, deliveredTo, avatarChats, mentionChats, voteChats, bioChats, silentChats, missedCalls, rawReactions)
            when (r) {
                ProcessResult.Saved -> saved++
                ProcessResult.Broken -> broken++
                ProcessResult.Transfer -> transfers++
                ProcessResult.Skipped -> {}
                // sonnet-фикс: временный сбой — запись остаётся в WAL,
                // доигрывается при следующем дрейне (без потери)
                ProcessResult.Retry -> continue
            }
            walDelete(context, msg)
        }

        // 2) вычерпываем очередь сервера ПОСТРАНИЧНО (1.1): каждая страница —
        //    сначала в WAL, обработана — подтверждаем серверу по id, и только
        //    затем следующая; страницы в памяти не копятся (450 сообщений
        //    прежде крутят одну и ту же первую страницу до OOM)
        var after = 0L
        var pages = 0
        while (true) {
            val page = session.api.drainPage(session.token, after)
            if (page.messages.isEmpty()) {
                if (page.more) Log.w(TAG, "дрейн: more=true при пустой странице — выходим")
                break
            }
            val ackIds = ArrayList<Long>(page.messages.size)
            for (message in page.messages) {
                val msg = WalMessage(newWalId(), message.from, message.kind, message.blob)
                walWrite(context, msg)
                val r = processIncoming(context, session, msg, byChat, reactionChats, editChats, pinChats, deleteChats, newDevices, mediaToFetch, deliveredTo, avatarChats, mentionChats, voteChats, bioChats, silentChats, missedCalls, rawReactions)
                when (r) {
                    ProcessResult.Saved -> saved++
                    ProcessResult.Broken -> broken++
                    ProcessResult.Transfer -> transfers++
                    ProcessResult.Skipped -> {}
                    // sonnet-фикс: временный сбой — в WAL остаётся, ack не ставим
                    ProcessResult.Retry -> continue
                }
                walDelete(context, msg)
                if (message.serverId > 0) ackIds += message.serverId
            }
            // подтверждение ПОСЛЕ обработки страницы (S4): обрыв не теряет
            // сообщения — неподтверждённые вернутся, dedup снимет дубль;
            // курсор after не даст повторить страницу внутри этого дрейна
            if (ackIds.isNotEmpty()) {
                try {
                    session.api.ackMessages(session.token, ackIds)
                } catch (e: Exception) {
                    Log.w(TAG, "ack дрейна не прошёл — записи вернутся с повтором", e)
                }
            }
            if (!page.more) break
            val lastId = page.messages.last().serverId
            if (lastId <= after) {
                // страховка: страница не сдвинула курсор — цикл прерывается
                Log.w(TAG, "дрейн: курсор не двигается (after=$after) — выходим")
                break
            }
            after = lastId
            if (++pages > DRAIN_MAX_PAGES) {
                Log.w(TAG, "дрейн: потолок страниц — выходим")
                break
            }
        }

        // 3) отложенные «пропущенные звонки» (Фаза GA): запись ПОСЛЕ
        //    обработки всей очереди — так синхронная копия с ответившего
        //    устройства уже в журнале и не даст ложной «пропущенности»
        for ((callId, peer) in missedCalls) {
            if (appendCallRecordOnce(session, peer, callId)) {
                saved++
                byChat[peer] = (byChat[peer] ?: 0) + 1
            }
        }

        // 4) доставка подтверждается один раз на отправителя (A14)
        for (sender in deliveredTo) {
            try {
                session.api.confirmDelivered(session.token, sender, session.deviceId)
            } catch (_: Exception) {
            }
        }

        // 5) AL9: реакции оживают только когда задели МОИ сообщения —
        // скан хвоста журнала чата один раз на чат (не на реакцию)
        val reactionNotices = ArrayList<chat.client.domain.ReactionNotify.Notice>()
        if (rawReactions.isNotEmpty()) {
            val scanned = HashMap<String, List<chat.client.domain.ReactionNotify.Record>>()
            for (n in rawReactions) {
                if (!chat.client.domain.ReactionNotify.authorizable(n, session.login)) continue
                val recs = scanned.getOrPut(n.chatId) {
                    try {
                        session.store.history(n.chatId, REACTION_SCAN_TAIL).map {
                            chat.client.domain.ReactionNotify.Record(it.from, it.timestamp, it.content)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "скан цели реакции не удался", e)
                        emptyList()
                    }
                }
                if (chat.client.domain.ReactionNotify.targetsMine(recs, n.targetRef, n.targetTs, session.login)) {
                    reactionNotices += n
                }
            }
        }
        return DrainReport(saved, byChat, reactionChats, editChats, pinChats, deleteChats, avatarChats, mentionChats, voteChats, bioChats, reactionNotices, silentChats, broken, transfers, newDevices, mediaToFetch)
    }

    // dedup-индекс не живёт вечно: после перезапуска процесса сеем его
    // из хвоста истории каждого чата (A1) — доигранный WAL не даст дубля
    private fun warmDedupFromHistory(session: SessionInfo) {
        try {
            for (chatId in session.store.chatIds()) {
                for (record in session.store.history(chatId, DEDUP_TAIL)) {
                    session.dedup.remember(session.dedupKey(record.from, record.content))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dedup warm: хвост истории не прочитан", e)
        }
    }

    // sonnet-фикс: Retry — временный локальный сбой (запись в журнал/IO);
    // запись в WAL остаётся, серверный ack не ставится — блоб не теряется
    private enum class ProcessResult { Saved, Broken, Transfer, Skipped, Retry }

    private fun processIncoming(
        context: Context,
        session: SessionInfo,
        msg: WalMessage,
        byChat: HashMap<String, Int>,
        reactionChats: HashSet<String>,
        editChats: HashSet<String>,
        pinChats: HashSet<String>,
        deleteChats: HashSet<String>,
        newDevices: MutableList<chat.client.domain.DeviceFormat.Decoded>,
        mediaToFetch: MutableList<Pair<String, chat.client.domain.MediaInfo>>,
        deliveredTo: MutableSet<String>,
        avatarChats: MutableSet<String> = HashSet(),
        mentionChats: MutableSet<String> = HashSet(),
        voteChats: MutableSet<String> = HashSet(),
        bioChats: MutableSet<String> = HashSet(),
        // Фаза X1: беззвучность последнего нового сообщения по чатам
        silentChats: MutableMap<String, Boolean> = HashMap(),
        // Фаза GA: отложенные «пропущенные звонки» — callId → пир
        missedCalls: MutableMap<String, String> = HashMap(),
        // AL9: сырые заметки о реакциях — цель проверяется после дрейна
        reactionNotices: MutableList<chat.client.domain.ReactionNotify.Notice>? = null,
        // GH4: чаты с эффектом отправки — для анимации в UI
        fxChats: MutableSet<String> = HashSet(),
    ): ProcessResult {
        val (from, kind, blob) = msg
        // недоверенный релей: from до валидации не используется как chatId (A1)
        if (!Validators.validLogin(from)) {
            Log.w(TAG, "отправитель ‚$from‘ не по регэкспу — блоб отброшен")
            return ProcessResult.Broken
        }
        // S6: префикс grp_ — пространство имён групповых чатов, живых
        // отправителей с ним не бывает (сервер такие логины не регистрирует)
        if (from.startsWith("grp_")) {
            Log.w(TAG, "отправитель ‚$from‘ занимает пространство групп — блоб отброшен")
            return ProcessResult.Broken
        }
        // локальная блокировка отправителя (A3): блоб сбрасываем,
        // доставку подтверждаем — у отправителя не копятся ретраи
        if (container.blockedSenders.isBlocked(from)) {
            deliveredTo += from
            return ProcessResult.Skipped
        }
        try {
            if (kind == "transfer") {
                // перенос принимаем ТОЛЬКО от своего аккаунта
                if (from != session.login) {
                    Log.w(TAG, "transfer от $from отклонён — жду от ${session.login}")
                    return ProcessResult.Broken
                }
                val plain = session.e2e.open(blob)
                val items = try {
                    TransferCodec.decodeChunk(plain, session.login)
                } catch (e: TransferCodec.Malformed) {
                    Log.w(TAG, "transfer отклонён: ${e.message}")
                    return ProcessResult.Broken
                }
                if (transferIndex == null) transferIndex = buildTransferIndex(session)
                for (item in items) {
                    val key = "${item.from}@${item.ts}@" + MessageRef.of(item.content)
                    val known = transferIndex!!.getOrPut(item.chat) { HashSet() }
                    if (known.add(key)) {
                        session.store.append(item.chat, MessageRecord(item.from, item.ts, item.content))
                    }
                }
                return ProcessResult.Transfer
            }

            val content = session.e2e.open(blob)

            // дедуп — проверка ПЕРЕД записью (A1), запоминание — ПОСЛЕ (C4):
            // карантин и отклонённые блобы не занимают индекс, реплей после
            // accept идёт как первая обработка. Ключ — от + расшифрованный
            // контент (шифроблоб при повторной отправке всегда новый)
            val dupKey = session.dedupKey(from, content)
            if (session.dedup.seen(dupKey)) {
                Log.d(TAG, "дубль от $from пропущен")
                deliveredTo += from // ретрай отправителя всё равно доставлен
                return ProcessResult.Skipped
            }

            // системное уведомление о новом устройстве — только от себя (A8):
            // посторонний не может подсунуть «к аккаунту привязано устройство»
            val deviceInfo = Session.deviceInfoOf(content)
            if (deviceInfo != null) {
                if (from == session.login) {
                    newDevices += deviceInfo
                    return ProcessResult.Saved
                }
                Log.w(TAG, "TYPE_DEVICE от $from отклонён — жду от ${session.login}")
                return ProcessResult.Broken
            }

            // TYPE_SYNC (C4): копия своего сообщения с другого своего
            // устройства. Только от session.login, идемпотентно по dedup
            // внутреннего контента; свои копии не дёргают уведомлений
            val envelope = chat.client.domain.Envelope.decode(content)
            if (envelope?.type == chat.client.domain.Envelope.TYPE_SYNC) {
                if (from != session.login) {
                    Log.w(TAG, "TYPE_SYNC от $from отклонён — жду от ${session.login}")
                    return ProcessResult.Broken
                }
                val sync = chat.client.domain.SyncFormat.decode(envelope.payload)
                    ?: return ProcessResult.Broken
                if (!Validators.validLogin(sync.chatId) && !sync.chatId.startsWith("grp_")) {
                    return ProcessResult.Broken
                }
                val innerKey = session.dedupKey(from, sync.content)
                if (!session.dedup.seen(innerKey)) {
                    // 1.6: синхронизированный групповой конверт создаёт/обновляет
                    // группу на ВТОРОМ своём устройстве — прежде она появлялась
                    // только после transfer, чат выглядел как 1:1 с grp_-id,
                    // и отправка в него падала. Своё устройство — без приглашения
                    reconcileGroupFromSync(sync.chatId, sync.content)
                    // копия с устройства-родня может нести СЛУЖЕБНОЕ событие
                    // (правка/закреп/удаление у всех): в журнал оно не
                    // пишется — применяем к своему журналу, как при прямом
                    // приёме, иначе в журнале оседает пузырь «неизвестный формат»
                    val controlInner = chat.client.domain.MessageCodec.innerContent(sync.content)
                    val controlEdit = chat.client.domain.EditFormat.of(controlInner)
                    val controlPin = if (controlEdit == null) chat.client.domain.PinFormat.of(controlInner) else null
                    val controlDelete = if (controlEdit == null && controlPin == null) {
                        chat.client.domain.DeleteFormat.of(controlInner)
                    } else {
                        null
                    }
                    // «О себе» (Фаза W2): копия своего bio с другого своего
                    // устройства — применяем, в журнал не пишем
                    val controlBio = if (controlEdit == null && controlPin == null && controlDelete == null) {
                        chat.client.domain.BioFormat.of(controlInner)
                    } else {
                        null
                    }
                    // аватар (Фаза U1): копия своего ava-конверта с другого
                    // своего устройства — применяем, в журнал не пишем
                    val syncAvatar = avatarEventOf(
                        (Session.parseIncoming(sync.content) as? MessageCodec.Parsed.Content)?.kind
                            ?: MessageCodec.Kind.Unsupported,
                    )
                    when {
                        controlEdit != null -> {
                            if (chat.client.domain.MessageEdit.apply(
                                    session.store, sync.chatId, session.login,
                                    controlEdit.targetRef, controlEdit.targetTs, controlEdit.newContent,
                                )
                            ) {
                                editChats += sync.chatId
                                repointPinAfterEdit(sync.chatId, controlEdit.targetRef, controlEdit.targetTs, controlEdit.newContent)
                            }
                        }
                        controlPin != null -> {
                            applyPin(session, sync.chatId, controlPin)
                            pinChats += sync.chatId
                        }
                        controlDelete != null -> {
                            if (applyIncomingDelete(session, context, sync.chatId, session.login, controlDelete)) {
                                deleteChats += sync.chatId
                            }
                        }
                        syncAvatar != null && syncAvatar.first == session.login -> {
                            applyUserAvatar(session.login, syncAvatar.second)
                            if (!syncAvatar.second.avatarRemove) {
                                collectMedia(mediaToFetch, session.login, MessageCodec.Kind.Media(syncAvatar.second))
                            }
                            avatarChats += session.login // «Избранное» + настройки (U1)
                        }
                        syncAvatar != null && syncAvatar.first == "grp" -> {
                            // групповой аватар с родственного устройства (А2):
                            // применяем к стору, в журнал не пишем — как при
                            // прямом приёме; прежде он оседал пузырём
                            val avaInfo = syncAvatar.second
                            when {
                                avaInfo.avatarRemove -> container.avatars.setGroup(sync.chatId, null)
                                // Фаза AE7: эмодзи-аватар — блоба нет
                                avaInfo.avatarEmoji != null ->
                                    container.avatars.setGroupEmoji(sync.chatId, avaInfo.avatarEmoji)
                                avaInfo.id.isNotEmpty() ->
                                    container.avatars.setGroup(sync.chatId, avaInfo)
                            }
                            if (!avaInfo.avatarRemove && avaInfo.avatarEmoji == null) {
                                collectMedia(mediaToFetch, sync.chatId, MessageCodec.Kind.Media(avaInfo))
                            }
                            avatarChats += sync.chatId // строки группы (U1)
                        }
                        controlBio != null -> {
                            container.bios.set(session.login, controlBio.text)
                            container.bios.setHours(session.login, controlBio.hoursJson) // GE6
                            bioChats += session.login // настройки + инфо-экран (W2)
                        }
                        else -> {
                            // GA: синхронная запись «звонок» с устройства-родня —
                            // без дубля по callId (свою могли написать этим же
                            // дрейном из отложенных); запись от имени пира,
                            // рендерится входящей
                            val syncCall = chat.client.domain.CallFormat.recordOf(sync.content)
                            if (syncCall != null) {
                                if (!hasCallRecord(session, sync.chatId, syncCall.callId)) {
                                    session.store.appendMonotonic(sync.chatId, sync.chatId, sync.content)
                                }
                            } else {
                                session.store.appendMonotonic(sync.chatId, from, sync.content)
                                val parsed = Session.parseIncoming(sync.content)
                                if (parsed is MessageCodec.Parsed.Content) {
                                    collectMedia(mediaToFetch, sync.chatId, parsed.kind)
                                }
                            }
                        }
                    }
                    session.dedup.remember(innerKey)
                }
                // внешний ключ тоже в индексе: повтор того же блоба — Skipped (A1)
                session.dedup.remember(dupKey)
                deliveredTo += from
                return ProcessResult.Saved
            }

            val groupMsg = MessageCodec.groupOf(content, allowLegacy = false)
            if (groupMsg != null) {
                val info = groupMsg.info
                val known = container.groups.byId(info.id)
                // сообщение ещё не принятого участника (A3): не теряется
                // как «вне состава», а ждёт в карантине до accept
                if (chat.client.domain.GroupRules.isUnconfirmedMember(
                        from, known, container.groups.pendingChange(info.id)?.members,
                    )
                ) {
                    quarantine(context, info.id, msg)
                    deliveredTo += from
                    return ProcessResult.Skipped
                }
                // 1.4: участник из БУДУЩЕГО состава, чей change-конверт ещё не
                // дошёл (или потерялся): сообщение не умирает как Broken —
                // ставим pendingChange от его имени и уводим в карантин
                // до подтверждения пользователем
                if (known != null && from !in known.members && from in info.members &&
                    chat.client.domain.GroupRules.revAcceptable(info.rev, known.rev)
                ) {
                    container.groups.setPendingChange(
                        info.id, from, info.members, info.rev,
                        info.broadcast, info.desc, info.slowMode, info.noForward,
                        info.admins, info.noReactions,
                    )
                    quarantine(context, info.id, msg)
                    deliveredTo += from
                    return ProcessResult.Skipped
                }
                if (!chat.client.domain.GroupRules.validateIncoming(session.login, from, info, known)) {
                    Log.w(TAG, "группа ${info.id}: отправитель $from вне состава — игнор")
                    return ProcessResult.Broken
                }
                // A4: покинутая группа не воскрешается конвертами участников
                // и не создаёт повторных приглашений
                if (container.groups.isLeft(info.id)) {
                    Log.d(TAG, "группа ${info.id} покинута — конверт от $from игнор")
                    deliveredTo += from
                    return ProcessResult.Skipped
                }
                // неизвестная группа — приглашение (C4), остальное — в
                // карантин (А4): аватар/bio/контент неизвестной группы не
                // применяются и медиа не качаются до решения пользователя
                if (known == null) {
                    container.groups.setInvite(
                        info.id, from, info.name, info.members, info.rev,
                        info.broadcast, info.desc, info.slowMode, info.noForward,
                        info.admins, info.noReactions,
                    )
                    quarantine(context, info.id, msg)
                    deliveredTo += from
                    return ProcessResult.Skipped // уведомлений о сообщении нет
                }
                // правка внутри группы — ДО разбора состава: конверт правки
                // несёт состав из оригинала, это не изменение членства.
                // Применяется только к сообщениям самого отправителя (A4)
                chat.client.domain.EditFormat.of(chat.client.domain.MessageCodec.innerContent(content))?.let { edit ->
                    if (chat.client.domain.MessageEdit.apply(
                            session.store, info.id, from, edit.targetRef, edit.targetTs, edit.newContent,
                        )
                    ) {
                        edit.targetRef?.let { r ->
                            edit.prev?.let { container.editHistory.record(info.id, r, it, System.currentTimeMillis()) }
                        }
                        editChats += info.id
                        repointPinAfterEdit(info.id, edit.targetRef, edit.targetTs, edit.newContent)
                    }
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // закреп внутри группы — применяем к локальному состоянию
                chat.client.domain.PinFormat.of(chat.client.domain.MessageCodec.innerContent(content))?.let { pin ->
                    applyPin(session, info.id, pin)
                    pinChats += info.id
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // «удалить у всех» внутри группы: цель — запись автора (A4)
                // или админ группы сносит чужое сообщение (AI3)
                chat.client.domain.DeleteFormat.of(chat.client.domain.MessageCodec.innerContent(content))?.let { del ->
                    if (applyIncomingDelete(session, context, info.id, from, del, admins = info.admins.toSet())) {
                        deleteChats += info.id
                    }
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // аватар группы/участника (Фаза U1): применяется без записи
                // в журнал; запись «обновил(а) фото» едет отдельным mc-конвертом
                val groupAvatar = avatarEventOf(groupMsg.inner)
                if (groupAvatar != null) {
                    val avaInfo = groupAvatar.second
                    when (groupAvatar.first) {
                        "grp" -> when {
                            avaInfo.avatarRemove -> container.avatars.setGroup(info.id, null)
                            // Фаза AE7: эмодзи-аватар — без блоба
                            avaInfo.avatarEmoji != null ->
                                container.avatars.setGroupEmoji(info.id, avaInfo.avatarEmoji)
                            avaInfo.id.isNotEmpty() -> container.avatars.setGroup(info.id, avaInfo)
                        }
                        from -> applyUserAvatar(from, avaInfo)
                        else -> {} // чужой логин — жулик, молча игнорируем
                    }
                    if (!avaInfo.avatarRemove && avaInfo.avatarEmoji == null) {
                        collectMedia(mediaToFetch, info.id, groupMsg.inner)
                    }
                    avatarChats += info.id // строки группы и участников (U1)
                    if (groupAvatar.first == from) avatarChats += from
                    session.dedup.remember(dupKey)
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // «О себе» участника внутри группы (Фаза W2): применяется без
                // записи в журнал; владелец обязан быть самим отправителем
                val groupBio = chat.client.domain.BioFormat.of(
                    chat.client.domain.MessageCodec.innerContent(content),
                )
                if (groupBio != null) {
                    container.bios.set(from, groupBio.text)
                    container.bios.setHours(from, groupBio.hoursJson) // GE6
                    bioChats += info.id
                    bioChats += from
                    session.dedup.remember(dupKey)
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // изменение состава (A9): удаления применяем молча,
                // добавления — только после подтверждения пользователем.
                // ревизия состава (A3): состав/имя применяются только от
                // конверта НОВЕЕ известного; конверты без v (=0) и старые
                // ревизии принимаются как сообщения, но ничего не перетирают
                if (chat.client.domain.GroupRules.shouldApplyComposition(info, known)) {
                    // 1.4: имя применяется из ЛЮБОГО конверта с ревизией новее
                    // известной — пропущенный rn-конверт лечится следующим
                    // сообщением; rn-запись остаётся для ленты «переименовал».
                    // Ревизия защищает от откатов (A3), конверты со старой
                    // ревизией имя не перетирают
                    if (info.name != known.name) {
                        container.groups.save(
                            known.copy(
                                name = info.name,
                                rev = info.rev, broadcast = info.broadcast,
                                desc = info.desc, slowMode = info.slowMode,
                            ),
                        )
                    }
                    // Фаза AB10 (аналог broadcast-каналов): режим едет в каждом
                    // конверте, применяется ревизионно; запись — сам change-конверт
                    if (info.broadcast != known.broadcast) {
                        val knownBc = container.groups.byId(info.id) ?: known
                        container.groups.save(
                            knownBc.copy(rev = info.rev, broadcast = info.broadcast, desc = info.desc, slowMode = info.slowMode),
                        )
                    }
                    // Фаза AC3 (аналог About у Telegram): описание едет в
                    // каждом конверте, применяется молча и ревизионно;
                    // запись в ленте — сам change-конверт
                    if (info.desc != known.desc) {
                        val knownGd = container.groups.byId(info.id) ?: known
                        container.groups.save(
                            knownGd.copy(rev = info.rev, desc = info.desc, slowMode = knownGd.slowMode),
                        )
                    }
                    // Фаза AC4 (аналог slow mode): период едет в каждом конверте
                    if (info.slowMode != known.slowMode) {
                        val knownSm = container.groups.byId(info.id) ?: known
                        container.groups.save(
                            knownSm.copy(rev = info.rev, slowMode = info.slowMode, desc = knownSm.desc),
                        )
                    }
                    // Фаза AD6 (аналог «Restrict saving content»): запрет
                    // пересылки едет в каждом конверте — применяется молча
                    // и ревизионно; запись — сам change-конверт
                    if (info.noForward != known.noForward) {
                        val knownNf = container.groups.byId(info.id) ?: known
                        container.groups.save(
                            knownNf.copy(rev = info.rev, noForward = info.noForward),
                        )
                    }
                    // AI3 (аналог admin rights у Telegram): список админов едет
                    // в каждом конверте — применяется молча и ревизионно
                    if (info.admins.toSet() != known.admins.toSet()) {
                        val knownAd = container.groups.byId(info.id) ?: known
                        container.groups.save(
                            knownAd.copy(
                                rev = info.rev,
                                admins = chat.client.domain.GroupRules.sanitizeAdmins(
                                    info.admins, knownAd.members,
                                ),
                            ),
                        )
                    }
                    // Фаза AJ (аналог Allowed Reactions: none): запрет
                    // реакций едет в каждом конверте — молча и ревизионно
                    if (info.noReactions != known.noReactions) {
                        val knownNr = container.groups.byId(info.id) ?: known
                        container.groups.save(
                            knownNr.copy(rev = info.rev, noReactions = info.noReactions),
                        )
                    }
                    // состав сравниваем МНОЖЕСТВОМ: разный порядок легитимен,
                    // прежнее сравнение списком «откатывало» только что
                    // применённое имя бессмысленным пересохранением (A3)
                    val membersChanged = known.members.toSet() != info.members.toSet()
                    if (membersChanged) {
                        val known2 = container.groups.byId(info.id) ?: known
                        val change = chat.client.domain.GroupRules.membershipChange(from, known2, info.members)
                        if (change.hasAdditions) {
                            container.groups.setPendingChange(
                                info.id, from, info.members, info.rev,
                                info.broadcast, info.desc, info.slowMode, info.noForward,
                                info.admins, info.noReactions,
                            )
                        } else {
                            container.groups.save(
                                known2.copy(
                                    members = info.members, rev = info.rev,
                                    broadcast = info.broadcast, desc = info.desc, slowMode = info.slowMode,
                                    noForward = info.noForward,
                                    admins = chat.client.domain.GroupRules.sanitizeAdmins(
                                        info.admins, info.members,
                                    ),
                                    noReactions = info.noReactions,
                                ),
                            )
                            container.groups.clearPendingChange(info.id)
                        }
                        // запись о составе хранит структуру, не готовый текст (B4):
                        // локализация — при показе. Для change-конвертов (M1) запись
                        // уже внутри самого сообщения — дубль не пишем
                        if (!change.isSame && !isGroupChangeEnvelope(groupMsg.inner)) {
                            session.store.appendMonotonic(
                                info.id, from,
                                SystemFormat.encodeChange(
                                    chat.client.domain.GroupChange(from, change.added, change.removed),
                                ),
                            )
                            // GH8: те же изменения — в журнал событий группы
                            val now = container.clock.now()
                            change.added.forEach {
                                container.groupEvents.record(
                                    info.id, chat.client.domain.GroupEventLog.Kind.MEMBER_ADDED, from, it, now,
                                )
                            }
                            change.removed.forEach {
                                container.groupEvents.record(
                                    info.id, chat.client.domain.GroupEventLog.Kind.MEMBER_REMOVED, from, it, now,
                                )
                            }
                        }
                    }
                }
                // Фаза AJ (Allowed Reactions: none): реакции в группе
                // с запретом не применяются вовсе — ни в журнал, ни в UI;
                // собеседник не узнает, что его реакцию съели
                if (container.groups.byId(info.id)?.noReactions == true) {
                    val innerEnvelope = chat.client.domain.Envelope.decode(
                        chat.client.domain.MessageCodec.innerContent(content),
                    )
                    if (innerEnvelope?.type == chat.client.domain.Envelope.TYPE_REACTION) {
                        session.dedup.remember(dupKey)
                        deliveredTo += from
                        return ProcessResult.Saved
                    }
                }
                // GE4: история внутри группы — в стор, в журнал не пишется
                val gStoryInfo = (groupMsg.inner as? MessageCodec.Kind.Media)?.info
                if (gStoryInfo != null && gStoryInfo.storyExp != null && gStoryInfo.avatarOwner == null) {
                    val now = container.clock.now()
                    container.storyStore.purge(now)
                    container.storyStore.add(
                        from, gStoryInfo.id, gStoryInfo.storyExp!!, now,
                        mine = from == session.login,
                        caption = gStoryInfo.storyCaption ?: "",
                        thumbB64 = gStoryInfo.thumbB64,
                    )
                    collectMedia(mediaToFetch, info.id, MessageCodec.Kind.Media(gStoryInfo))
                    session.dedup.remember(dupKey)
                    return ProcessResult.Saved
                }
                session.store.appendMonotonic(info.id, from, content)
                session.dedup.remember(dupKey)
                // реакции и служебные записи — не «новое сообщение» (A3):
                // без уведомления и события Incoming
                if (!isQuietGroupInner(groupMsg.inner, content)) {
                    byChat[info.id] = (byChat[info.id] ?: 0) + 1
                    // Фаза X1: последнее новое сообщение решает — тихое
                    // уведомление или обычное (как у Telegram silent)
                    silentChats[info.id] = kindIsSilent(groupMsg.inner)
                    // @упоминание меня ИЛИ @all в группе (Фаза V4 + AH6):
                    // даже мьютнутая группа обязана дёрнуть уведомление
                    if (chat.client.domain.MessageMention.mentions(groupMsg.inner, session.login) ||
                        chat.client.domain.MessageMention.mentionsAll(groupMsg.inner)
                    ) {
                        mentionChats += info.id
                        // Фаза AE4: @-бейдж в списке — счётчик непрочитанных
                        container.unread.addMentions(info.id, 1)
                    }
                } else if (chat.client.domain.VoteFormat.of(
                    chat.client.domain.MessageCodec.innerContent(content)
                ) != null) {
                    // голос в опросе внутри группы — тихое обновление (W1)
                    voteChats += info.id
                } else {
                    // AL9: реакция внутри группы — тихая запись, но чат с ней
                    // обновляется, а задевшая МОЮ — заметка для уведомления
                    val inner = chat.client.domain.Envelope.decode(
                        chat.client.domain.MessageCodec.innerContent(content),
                    )
                    if (inner?.type == chat.client.domain.Envelope.TYPE_REACTION) {
                        reactionChats += info.id
                        val parsed = chat.client.domain.MessageCodec.parse(
                            chat.client.domain.MessageCodec.innerContent(content),
                        )
                        if (parsed is MessageCodec.Parsed.ReactionOnly) {
                            reactionNotices?.add(
                                chat.client.domain.ReactionNotify.Notice(
                                    info.id, from, parsed.reaction.emoji,
                                    parsed.reaction.targetRef, parsed.reaction.targetTs,
                                ),
                            )
                        }
                    }
                }
                deliveredTo += from
                collectMedia(mediaToFetch, info.id, groupMsg.inner)
            } else {
                // правка 1:1: применяется к журналу, не сохраняется.
                // Цель — только сообщение отправителя кадра (A4)
                chat.client.domain.EditFormat.of(content)?.let { edit ->
                    if (chat.client.domain.MessageEdit.apply(
                            session.store, from, from, edit.targetRef, edit.targetTs, edit.newContent,
                        )
                    ) {
                        edit.targetRef?.let { r ->
                            edit.prev?.let { container.editHistory.record(from, r, it, System.currentTimeMillis()) }
                        }
                        editChats += from
                        repointPinAfterEdit(from, edit.targetRef, edit.targetTs, edit.newContent)
                    }
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // закреп 1:1: применяется к локальному состоянию
                chat.client.domain.PinFormat.of(content)?.let { pin ->
                    applyPin(session, from, pin)
                    pinChats += from
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // «удалить у всех» 1:1: цель — только запись автора (A4)
                chat.client.domain.DeleteFormat.of(content)?.let { del ->
                    if (applyIncomingDelete(session, context, from, from, del)) {
                        deleteChats += from
                    }
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // аватар отправителя (Фаза U1): применяем к стору, в журнал
                // не пишем; владелец обязан быть самим отправителем
                val directAvatar = avatarEventOf(
                    (Session.parseIncoming(content) as? MessageCodec.Parsed.Content)?.kind
                        ?: MessageCodec.Kind.Unsupported,
                )
                if (directAvatar != null) {
                    if (directAvatar.first == from) {
                        applyUserAvatar(from, directAvatar.second)
                        if (!directAvatar.second.avatarRemove) {
                            collectMedia(mediaToFetch, from, MessageCodec.Kind.Media(directAvatar.second))
                        }
                        avatarChats += from // подпись кэша учитывает аватар (U1)
                    }
                    session.dedup.remember(dupKey)
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // «О себе» отправителя (Фаза W2): применяем к стору, в журнал
                // не пишем; владелец обязан быть самим отправителем
                val directBio = chat.client.domain.BioFormat.of(content)
                if (directBio != null) {
                    container.bios.set(from, directBio.text)
                    container.bios.setHours(from, directBio.hoursJson) // GE6
                    bioChats += from
                    session.dedup.remember(dupKey)
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // Фаза AA8 (аналог draft-синка у Telegram): черновик с
                // другого СВОЕГО устройства — применяется к стору, в журнал
                // не пишется; чужие TYPE_DRAFT игнорируются
                val draftSync = chat.client.domain.DraftFormat.of(content)
                if (draftSync != null) {
                    if (from != session.login) {
                        Log.w(TAG, "TYPE_DRAFT от $from отклонён — жду от ${session.login}")
                        return ProcessResult.Broken
                    }
                    if (container.chatPrefs.setDraftSynced(draftSync.chatId, draftSync.text, draftSync.at)) {
                        container.invalidateChats(setOf(draftSync.chatId))
                    }
                    session.dedup.remember(dupKey)
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // Фаза AJ (аналог синка папок у Telegram): набор папок с
                // другого СВОЕГО устройства — применяется к prefs по ревизии,
                // в журнал не пишется; чужие TYPE_FOLDERS игнорируются
                val foldersSync = chat.client.domain.FolderSyncFormat.of(content)
                if (foldersSync != null) {
                    if (from != session.login) {
                        Log.w(TAG, "TYPE_FOLDERS от $from отклонён — жду от ${session.login}")
                        return ProcessResult.Broken
                    }
                    if (foldersSync.rev > container.appPrefs.foldersRev()) {
                        container.appPrefs.setFoldersRev(foldersSync.rev)
                        container.appPrefs.setFoldersJson(
                            chat.client.domain.ChatFolders.toJson(foldersSync.folders),
                        )
                        container.foldersTick.value = container.foldersTick.value + 1
                    }
                    session.dedup.remember(dupKey)
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                // Фаза GA: событие звонка — без записи в журнал. Живой звонок
                // уходит в CallRouter (Фаза GB повесит CallManager), «пропущенный»
                // оседает отложенной записью в конце дрейна. Мусорный TYPE_CALL
                // (будущее событие от новее клиента) в журнал не идёт тоже
                // GH4: эффект отправки (TYPE_FX) — не в журнал, помечаем чат
                chat.client.domain.SendEffect.of(content)?.let {
                    fxChats += from
                    session.dedup.remember(dupKey)
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                val callEvent = chat.client.domain.CallFormat.of(content)
                if (callEvent == null) {
                    if (chat.client.domain.Envelope.decode(content)?.type == chat.client.domain.Envelope.TYPE_CALL) {
                        return ProcessResult.Broken
                    }
                } else {
                    return processCallEvent(session, from, callEvent, missedCalls)
                }
                // Фаза AG3 + 3.8: первый контакт от незнакомца — архив и мьют.
                // Контакт, добавленный по QR/из контактов, — НЕ незнакомец:
                // запомненные ключи (TOFU) или открытый ранее чат (lastRead)
                // исключают его из автомуота
                if (from != session.login && !container.chatPrefs.chatKnown(from)) {
                    val peerKnown = container.knownKeys.known(from).isNotEmpty() ||
                        container.unread.lastRead(from) > 0L
                    if (!peerKnown && container.appPrefs.archiveMuteStrangers() &&
                        session.store.history(from, 1).isEmpty() &&
                        container.chatPrefs.name(from) == null
                    ) {
                        container.chatPrefs.setArchived(from, true)
                        container.chatPrefs.setMutedUntil(from, Long.MAX_VALUE)
                    }
                    // флаг ставим всегда: проверка истории — один раз на чат
                    container.chatPrefs.setChatKnown(from, true)
                }
                // GE4: история (медиа с полем "st") — в стор историй,
                // в журнал не пишется; блоб качается как обычное медиа
                val parsedEarly = Session.parseIncoming(content)
                val storyInfo = (parsedEarly as? MessageCodec.Parsed.Content)?.kind
                    ?.let { it as? MessageCodec.Kind.Media }?.info
                if (storyInfo != null && storyInfo.storyExp != null && storyInfo.avatarOwner == null) {
                    val now = container.clock.now()
                    container.storyStore.purge(now)
                    container.storyStore.add(
                        from, storyInfo.id, storyInfo.storyExp!!, now,
                        mine = from == session.login,
                        caption = storyInfo.storyCaption ?: "",
                        thumbB64 = storyInfo.thumbB64,
                    )
                    collectMedia(mediaToFetch, from, MessageCodec.Kind.Media(storyInfo))
                    session.dedup.remember(dupKey)
                    deliveredTo += from
                    return ProcessResult.Saved
                }
                session.store.appendMonotonic(from, from, content)
                session.dedup.remember(dupKey)
                deliveredTo += from
                val parsed = parsedEarly
                when (parsed) {
                    is MessageCodec.Parsed.ReactionOnly -> {
                        reactionChats += from
                        // AL9: реакция на МОЁ сообщение — заметка для шторки
                        reactionNotices?.add(
                            chat.client.domain.ReactionNotify.Notice(
                                from, from, parsed.reaction.emoji,
                                parsed.reaction.targetRef, parsed.reaction.targetTs,
                            ),
                        )
                    }
                    // голос в опросе (Фаза W1): не «новое сообщение» — без
                    // уведомления, только обновление открытого диалога
                    is MessageCodec.Parsed.VoteOnly -> voteChats += from
                    is MessageCodec.Parsed.Content ->
                        collectMedia(mediaToFetch, from, parsed.kind)
                    else -> {}
                }
                if (parsed !is MessageCodec.Parsed.ReactionOnly &&
                    parsed !is MessageCodec.Parsed.VoteOnly
                ) {
                    byChat[from] = (byChat[from] ?: 0) + 1
                    // Фаза X1: беззвучность последнего нового сообщения
                    if (parsed is MessageCodec.Parsed.Content) {
                        silentChats[from] = kindIsSilent(parsed.kind)
                    }
                    // @упоминание себя и в 1:1 собираем единообразно (Фаза V4):
                    // пробивает ли мьют — решает Notifier (только группы)
                    if (parsed is MessageCodec.Parsed.Content &&
                        chat.client.domain.MessageMention.mentions(parsed.kind, session.login)
                    ) {
                        mentionChats += from
                        // Фаза AE4: @-бейдж в списке — и в 1:1 единообразно
                        container.unread.addMentions(from, 1)
                    }
                    // GH5: автоответчик — на входящий текст 1:1 в окно тихих
                    // часов шлём предустановленный ответ не чаще cooldown
                    if (parsed is MessageCodec.Parsed.Content &&
                        (parsed.kind is MessageCodec.Kind.Text || parsed.kind is MessageCodec.Kind.Reply) &&
                        from != session.login
                    ) {
                        maybeAutoReply(session, from, (parsed.kind as? MessageCodec.Kind.Text)?.text ?: "")
                    }
                }
            }
            return ProcessResult.Saved
        } catch (e: java.security.GeneralSecurityException) {
            // не расшифровано — необратимо (TOFU-разрыв/чужой ключ): дроп
            Log.e(TAG, "сообщение от $from не расшифровано", e)
            return ProcessResult.Broken
        } catch (e: Exception) {
            // sonnet-фикс: локальный сбой (IO журнала и пр.) — НЕ теряем блоб:
            // запись остаётся в WAL, серверу ack не ставится — доиграется позже
            Log.e(TAG, "сообщение от $from временно не сохранено — повтор дрейна", e)
            return ProcessResult.Retry
        }
    }

    // приём call-события (Фаза GA): гейт решает судьбу события, журнал
    // событие не видит; дедуп блоба и подтверждение доставки — как у
    // остальных контрольных событий (прецедент bio/edit/delete)
    private fun processCallEvent(
        session: SessionInfo,
        from: String,
        event: chat.client.domain.CallFormat.Event,
        missedCalls: MutableMap<String, String>,
    ): ProcessResult {
        when (val verdict = session.calls.decide(from == session.login, event, System.currentTimeMillis())) {
            is chat.client.domain.CallVerdict.Mute ->
                Log.d(TAG, "звонок ${event.callId}: терминальный дубль ${event.ev} от $from")
            is chat.client.domain.CallVerdict.Missed -> {
                missedCalls[event.callId] = from
                container.callRouter?.onCallEvent(from, event)
            }
            is chat.client.domain.CallVerdict.Live ->
                container.callRouter?.onCallEvent(from, event)
        }
        return ProcessResult.Saved
    }

    // запись «пропущенный звонок» в журнал чата пира; дубль по callId — нет.
    // Запись от имени пира: рендерится входящей, как у Telegram
    private fun appendCallRecordOnce(session: SessionInfo, chatId: String, callId: String): Boolean {
        if (hasCallRecord(session, chatId, callId)) return false
        session.store.appendMonotonic(
            chatId, chatId,
            chat.client.domain.CallFormat.encodeRecord(callId, chat.client.domain.CallFormat.STATUS_MISSED),
        )
        return true
    }

    // уже есть запись о звонке с этим callId? скан хвоста журнала чата
    private fun hasCallRecord(session: SessionInfo, chatId: String, callId: String): Boolean {
        try {
            for (record in session.store.history(chatId, CALL_RECORD_SCAN)) {
                if (chat.client.domain.CallFormat.recordCallId(record.content) == callId) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "скан записей звонка в $chatId не прошёл", e)
            return true // при сбое чтения не пишем потенциальный дубль
        }
        return false
    }

    // Фаза X1: беззвучная отправка в разобранном виде — текст или вложение
    private fun kindIsSilent(kind: MessageCodec.Kind): Boolean = when (kind) {
        is MessageCodec.Kind.Reply -> kind.silent
        is MessageCodec.Kind.Media -> kind.info.silent
        else -> false
    }

    private fun collectMedia(
        out: MutableList<Pair<String, chat.client.domain.MediaInfo>>,
        chatId: String,
        kind: MessageCodec.Kind,
    ) {
        if (kind is MessageCodec.Kind.Media && kind.info.avatarOwner == null && kind.info.id.isNotEmpty()) {
            out += chatId to kind.info
        } else if (kind is MessageCodec.Kind.Media && kind.info.avatarOwner != null &&
            !kind.info.avatarRemove && kind.info.avatarEmoji == null
        ) {
            // аватар (Фаза U1): блоб качаем как обычное медиа; у эмодзи-
            // аватара (Фаза AE7) блоба нет — не собираем
            out += chatId to kind.info
        }
    }

    // ─── аватары (Фаза U1, аналог фото профиля Telegram) ───

    // ava-событие из содержимого: null — не аватар; пара (владелец, дескриптор)
    // 1.6: группа из TYPE_SYNC-копии — создаётся/обновляется на втором
    // своём устройстве без приглашения; ревизия защищает от откатов (A3)
    private fun reconcileGroupFromSync(chatId: String, content: ByteArray) {
        if (!chatId.startsWith("grp_")) return
        val envelope = chat.client.domain.Envelope.decode(content) ?: return
        if (envelope.type != chat.client.domain.Envelope.TYPE_GROUP) return
        val info = chat.client.domain.GroupFormat.decodeV2(envelope.payload)?.group ?: return
        if (info.id != chatId) return
        val known = container.groups.byId(chatId)
        if (known == null) {
            container.groups.save(
                GroupChat(
                    info.id, info.name, info.members, info.rev,
                    info.broadcast, info.desc, info.slowMode, info.noForward,
                    chat.client.domain.GroupRules.sanitizeAdmins(info.admins, info.members),
                    info.noReactions,
                ),
            )
        } else if (info.rev > known.rev) {
            container.groups.save(
                known.copy(
                    name = info.name, members = info.members, rev = info.rev,
                    broadcast = info.broadcast, desc = info.desc,
                    slowMode = info.slowMode, noForward = info.noForward,
                    admins = chat.client.domain.GroupRules.sanitizeAdmins(info.admins, info.members),
                    noReactions = info.noReactions,
                ),
            )
        }
    }

    private fun avatarEventOf(kind: MessageCodec.Kind): Pair<String, chat.client.domain.MediaInfo>? {
        val info = (kind as? MessageCodec.Kind.Media)?.info ?: return null
        if (info.avatarOwner == null) return null
        return info.avatarOwner to info
    }

    // применение аватара юзера: смена или снятие; false — битый дескриптор
    private fun applyUserAvatar(owner: String, info: chat.client.domain.MediaInfo): Boolean {
        if (info.avatarRemove) {
            container.avatars.setUser(owner, null)
            return true
        }
        if (info.id.isEmpty()) return false
        container.avatars.setUser(owner, info)
        return true
    }

    // конверт ava-события: смена (с дескриптором блоба) или снятие (del);
    // Фаза AE7: эмодзи-аватар — без блоба, ava="e:<эмодзи>"
    private fun avatarPayload(owner: String, info: chat.client.domain.MediaInfo?): ByteArray {
        val media = if (info != null) {
            chat.client.domain.MediaInfo(
                info.id, info.key, info.size, info.width, info.height, info.thumbB64,
                avatarOwner = owner,
            )
        } else {
            chat.client.domain.MediaInfo(
                "", ByteArray(0), 0, 0, 0, "", avatarOwner = owner, avatarRemove = true,
            )
        }
        return chat.client.domain.MediaJson.message(media)
    }

    // Фаза AE7: конверт эмодзи-аватара группы; null — снятие (del)
    private fun avatarEmojiPayload(emoji: String?): ByteArray {
        val media = if (emoji != null) {
            chat.client.domain.MediaInfo(
                "", ByteArray(0), 0, 0, 0, "",
                avatarOwner = "grp", avatarEmoji = emoji,
            )
        } else {
            chat.client.domain.MediaInfo(
                "", ByteArray(0), 0, 0, 0, "", avatarOwner = "grp", avatarRemove = true,
            )
        }
        return chat.client.domain.MediaJson.message(media)
    }

    // свой аватар: применяется локально и рассылается всем своим чатам
    // (1:1 — прямым конвертом, группы — групповым; свои устройства — через
    // «Избранное»-путь sendPeer). Мгновенная отправка, как у переименования:
    // офлайн-сбой — снек, пользователь повторит. Возврат — (доставлено, всего)
    fun setUserAvatar(session: SessionInfo, info: chat.client.domain.MediaInfo?): Pair<Int, Int> {
        container.avatars.setUser(session.login, info)
        val payload = avatarPayload(session.login, info)
        var delivered = 0
        var total = 0
        val ids = (session.store.chatIds() + container.groups.all().map { it.id }).distinct()
        for (chatId in ids) {
            if (chatId.isEmpty()) continue
            total++
            try {
                val group = container.groups.byId(chatId)
                if (group != null) {
                    val wrapped = group.wrap(payload)
                    sendGroupEnvelope(session, chatId, wrapped, null)
                } else {
                    // в т.ч. свой логин: копия на свои устройства (без журнала —
                    // ava-перехват применит аватар и не запишет пузырь)
                    sendPeer(session, chatId, payload)
                }
                delivered++
            } catch (e: KeyChangedException) {
                throw e // проброс: смена ключей — баннер TOFU наверху
            } catch (_: Exception) {
            }
        }
        return delivered to total
    }

    // GE4: история (фото с "st"-сроком) — фан-аут всем чатам как аватар;
    // в журнал не пишется, на своих устройствах приземлится с mine=true
    fun sendStoryToAll(session: SessionInfo, info: chat.client.domain.MediaInfo): Pair<Int, Int> {
        val payload = chat.client.domain.MediaJson.message(info)
        var delivered = 0
        var total = 0
        val ids = (session.store.chatIds() + container.groups.all().map { it.id }).distinct()
        for (chatId in ids) {
            if (chatId.isEmpty()) continue
            total++
            try {
                val group = container.groups.byId(chatId)
                if (group != null) {
                    sendGroupEnvelope(session, chatId, group.wrap(payload), null)
                } else {
                    sendPeer(session, chatId, payload)
                }
                delivered++
            } catch (e: KeyChangedException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return delivered to total
    }

    // аватар группы: локально + mc-запись «обновил(а) фото» участникам
    // (как переименование) + сам ava-конверт с блобом
    fun setGroupAvatar(session: SessionInfo, chatId: String, info: chat.client.domain.MediaInfo?) {
        val group = container.groups.byId(chatId) ?: return
        container.avatars.setGroup(chatId, info)
        val record = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(
                session.login, emptyList(), emptyList(), avatar = info != null,
            ),
        )
        val wrappedRecord = group.wrap(record)
        try {
            sendGroupEnvelope(session, chatId, wrappedRecord, null)
        } catch (_: Exception) {
            // некритично: применится со следующим конвертом
        }
        journalOwn(session, chatId, wrappedRecord)
        if (info != null) {
            val wrappedAvatar = group.wrap(avatarPayload("grp", info))
            try {
                sendGroupEnvelope(session, chatId, wrappedAvatar, null)
            } catch (_: Exception) {
            }
        }
    }

    // Фаза AE7 (аналог эмодзи-аватара группы у Telegram): локально +
    // mc-запись «обновил(а) фото» + ava-конверт без блоба; null — снятие
    fun setGroupAvatarEmoji(session: SessionInfo, chatId: String, emoji: String?) {
        val group = container.groups.byId(chatId) ?: return
        val clean = emoji?.let { chat.client.domain.AvatarEmoji.sanitize(it) }
        container.avatars.setGroupEmoji(chatId, clean)
        val record = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(
                session.login, emptyList(), emptyList(), avatar = clean != null,
            ),
        )
        val wrappedRecord = group.wrap(record)
        try {
            sendGroupEnvelope(session, chatId, wrappedRecord, null)
        } catch (_: Exception) {
        }
        journalOwn(session, chatId, wrappedRecord)
        val wrappedAvatar = group.wrap(avatarEmojiPayload(clean))
        try {
            sendGroupEnvelope(session, chatId, wrappedAvatar, null)
        } catch (_: Exception) {
        }
    }

    // ─── «О себе» (Фаза W2, аналог bio Telegram) ───

    // свой bio: применяется локально и рассылается всем своим чатам
    // (как аватар U1); пустой текст — снятие. GE6: бизнес-часы — опциональным
    // полем "bh" того же конверта. Возврат — (доставлено, всего)
    fun setUserBio(session: SessionInfo, bio: String?, hoursJson: String? = null): Pair<Int, Int> {
        val sanitized = chat.client.domain.BioFormat.sanitize(bio ?: "")
        container.bios.set(session.login, sanitized)
        container.bios.setHours(session.login, hoursJson)
        val payload = chat.client.domain.BioFormat.encode(sanitized ?: "", hoursJson)
        var delivered = 0
        var total = 0
        val ids = (session.store.chatIds() + container.groups.all().map { it.id }).distinct()
        for (chatId in ids) {
            if (chatId.isEmpty()) continue
            total++
            try {
                val group = container.groups.byId(chatId)
                if (group != null) {
                    val wrapped = group.wrap(payload)
                    sendGroupEnvelope(session, chatId, wrapped, null)
                } else {
                    // в т.ч. свой логин: копия на свои устройства применит bio
                    sendPeer(session, chatId, payload)
                }
                delivered++
            } catch (e: KeyChangedException) {
                throw e // проброс: смена ключей — баннер TOFU наверху
            } catch (_: Exception) {
            }
        }
        return delivered to total
    }

    // ─── WAL дрейна: сырые блобы до обработки, удаление после ───

    // id фиксируется при записи (A1): имя файла стабильно между
    // walWrite и walDelete, повторный вызов не создаёт второй файл
    class WalMessage(val id: String, val from: String, val kind: String, val blob: ByteArray) {
        operator fun component1() = from
        operator fun component2() = kind
        operator fun component3() = blob
    }

    private fun newWalId(): String =
        System.currentTimeMillis().toString(36) + "-" + UUID.randomUUID().toString().substring(0, 8)

    private fun walDir(context: Context) = File(context.filesDir, "drainwal")

    private fun walFile(context: Context, id: String): File = File(walDir(context), "$id.wal")

    private fun walWrite(context: Context, msg: WalMessage) {
        try {
            val dir = walDir(context)
            dir.mkdirs()
            DataOutputStream(walFile(context, msg.id).outputStream().buffered()).use { out ->
                out.writeUTF(msg.from)
                out.writeUTF(msg.kind)
                out.writeInt(msg.blob.size)
                out.write(msg.blob)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAL: запись не удалась", e)
        }
    }

    private fun walDelete(context: Context, msg: WalMessage) {
        walFile(context, msg.id).delete()
    }

    // читает журнал, НЕ удаляя файлы (A1): удаление — только после
    // успешной обработки в drain. Битые файлы вычищаются сразу
    private fun walLoad(context: Context): List<WalMessage> {
        val dir = walDir(context)
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<WalMessage>()
        for (file in files.sortedBy { it.name }) {
            try {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    val from = input.readUTF()
                    val kind = input.readUTF()
                    val len = input.readInt()
                    if (len < 0 || len > 12 * 1024 * 1024) throw IllegalStateException("битый WAL")
                    val blob = ByteArray(len)
                    input.readFully(blob)
                    out += WalMessage(file.name.removeSuffix(".wal"), from, kind, blob)
                }
            } catch (e: Exception) {
                Log.w(TAG, "битый WAL удалён", e)
                file.delete()
            }
        }
        return out
    }

    private fun buildTransferIndex(session: SessionInfo): HashMap<String, HashSet<String>> {
        val index = HashMap<String, HashSet<String>>()
        for (chatId in session.store.chatIds()) {
            val known = index.getOrPut(chatId) { HashSet() }
            for (record in session.store.history(chatId, Int.MAX_VALUE)) {
                known += "${record.from}@${record.timestamp}@" + MessageRef.of(record.content)
            }
        }
        return index
    }

    // ─── докачка транзитного медиа после сохранения и уведомлений (A14) ───

    fun fetchMedia(context: Context, session: SessionInfo, info: chat.client.domain.MediaInfo) {
        if (chat.client.media.Media.exists(context, info.id)) return
        try {
            downloadAndStoreMedia(context, session.api, session.token, info.id, info.key)
        } catch (_: Exception) {
        }
    }

    // Фаза X5 (аналог Data Saver у Telegram): фоновая докачка ограничена
    // настройкой «автозагрузка медиа» — всегда / Wi-Fi / никогда.
    // Просмотр по тапу качает безусловно — через getBytes, не этот путь.
    // Фаза Y3 (аналог «Save to Gallery»): включённый автосейв чата
    // качает фото/видео ВСЕГДА и пишет копию в системную галерею
    fun fetchMediaAuto(
        context: Context,
        session: SessionInfo,
        chatId: String,
        info: chat.client.domain.MediaInfo,
    ) {
        // Фаза AB1: одноразовое не автосохраняем — просмотр только в приложении
        val autoSave = chat.client.domain.GalleryAutoSave.savable(info) &&
            !info.viewOnce &&
            container.chatPrefs.autoSaveToGallery(chatId)
        if (!autoSave) {
            // AI8 (аналог матрицы Auto-Download у Telegram): политика
            // по типу вложения; незаданный тип наследует глобальную
            val kind = chat.client.domain.MediaAutoDownloadPolicy.kindOf(info)
            val mode = chat.client.domain.MediaAutoDownloadPolicy.modeOf(
                kind, container.appPrefs.mediaAutoTypes(), container.appPrefs.mediaAutoDownload(),
            )
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val metered = try {
                cm == null || cm.isActiveNetworkMetered
            } catch (_: Exception) {
                true // сети нет — считаем платной, докачка не стартует
            }
            if (!chat.client.domain.MediaAutoDownloadPolicy.shouldAutoDownload(mode, metered)) return
            // AK3: потолок размера фоновых докачек; автосейв в галерею не трогаем
            if (!chat.client.domain.MediaAutoCap.within(container.appPrefs.mediaAutoCapKb(), info.size)) return
        }
        if (chat.client.media.Media.exists(context, info.id)) return
        val bytes = try {
            downloadAndStoreMedia(context, session.api, session.token, info.id, info.key)
        } catch (_: Exception) {
            null
        } ?: return
        if (autoSave && info.avatarOwner == null) {
            try {
                val mime = info.mime ?: if (info.type == "media") "image/jpeg" else "application/octet-stream"
                val name = chat.client.media.Gallery.displayName(
                    chat.client.media.Gallery.kindOf(mime),
                    chat.client.media.Gallery.extOf(info.name, mime),
                )
                // 3.2: результат с причиной; автосейв молча пропускает отказ
                chat.client.media.Gallery.save(context, bytes, mime, name) == chat.client.media.Gallery.SaveResult.OK
            } catch (_: Exception) {
                // сейв не критичен: медиа уже в зашифрованном кэше
            }
        }
    }

    // ─── реакции ───

    // Реакция улетает собеседнику (TYPE_REACTION), локальная копия — в журнал.
    // Снятие — тоже сообщение, но с пустым эмодзи (A2): обе стороны
    // убирают последнюю запись автора с цели.
    fun toggleReaction(
        session: SessionInfo,
        chatId: String,
        targetRef: String,
        targetContent: ByteArray,
        emoji: String?, // null → снять реакцию
        window: Int = 2000, // окно поиска своей прежней реакции (C1)
    ) {
        // ищем свою прежнюю реакцию на эту цель
        val mine = session.store.history(chatId, window)
            .firstOrNull { record ->
                record.from == session.login &&
                    (Session.parseIncoming(record.content) as? MessageCodec.Parsed.ReactionOnly)
                        ?.reaction?.targetRef?.let { MessageRef.sameRef(it, targetRef) } == true
            }
        if (mine != null) {
            session.store.deleteRecord(chatId, mine.timestamp, mine.content)
        }
        val payload = chat.client.domain.ReactionFormat.encode(
            chat.client.domain.ReactionFormat.Target(targetRef, null),
            emoji ?: "",
        )
        // группа: конверт строим один раз — уходит участникам и в журнал
        val group = container.groups.byId(chatId)
        val record = if (group != null) {
            group.wrap(payload)
        } else {
            payload
        }
        try {
            if (group != null) {
                sendGroupEnvelope(session, chatId, record, null)
            } else {
                sendPeer(session, chatId, payload)
            }
        } catch (e: KeyChangedException) {
            throw e
        } catch (_: Exception) {
            // реакция не критична: локальная копия всё равно останется
        }
        journalOwn(session, chatId, record)
    }

    // ─── опросы (Фаза W1, аналог TYPE_POLL/voteUpdates Telegram) ───

    // голос в опросе: улетает собеседнику, локальная копия — в журнал.
    // Прежняя своя запись на опрос удаляется — одна актуальная на автора
    // (как у реакций A2); снятие — choice = -1
    fun sendVote(
        session: SessionInfo,
        chatId: String,
        pollRef: String,
        choice: Int, // индекс варианта или -1 — снять голос
        window: Int = 2000, // окно поиска своей прежней записи (C1)
    ) {
        val mine = session.store.history(chatId, window)
            .firstOrNull { record ->
                record.from == session.login &&
                    (Session.parseIncoming(record.content) as? MessageCodec.Parsed.VoteOnly)
                        ?.vote?.targetRef?.let { MessageRef.sameRef(it, pollRef) } == true
            }
        if (mine != null) {
            session.store.deleteRecord(chatId, mine.timestamp, mine.content)
        }
        dispatchVoteEvent(session, chatId, chat.client.domain.VoteFormat.encode(pollRef, choice))
    }

    // Фаза AA9 (аналог multiple_choice у Telegram): голос многоответного
    // опроса — на пару (я, вариант); своя прежняя запись ПАРЫ заменяется,
    // снятие — событие "r":1, агрегация берёт последнее на пару
    fun sendVoteMulti(
        session: SessionInfo,
        chatId: String,
        pollRef: String,
        choice: Int,
        add: Boolean,
        window: Int = 2000,
    ) {
        val mine = session.store.history(chatId, window)
            .filter { record ->
                record.from == session.login &&
                    (Session.parseIncoming(record.content) as? MessageCodec.Parsed.VoteOnly)
                        ?.vote?.let { v ->
                            v.targetRef?.let { MessageRef.sameRef(it, pollRef) } == true &&
                                v.choice == choice
                        } == true
            }
        for (record in mine) {
            session.store.deleteRecord(chatId, record.timestamp, record.content)
        }
        dispatchVoteEvent(
            session, chatId,
            chat.client.domain.VoteFormat.encode(pollRef, choice, remove = !add),
        )
    }

    // отправка события-голоса: сеть + локальная копия в журнале
    private fun dispatchVoteEvent(session: SessionInfo, chatId: String, payload: ByteArray) {
        val group = container.groups.byId(chatId)
        val record = if (group != null) {
            group.wrap(payload)
        } else {
            payload
        }
        try {
            if (group != null) {
                sendGroupEnvelope(session, chatId, record, null)
            } else {
                sendPeer(session, chatId, payload)
            }
        } catch (e: KeyChangedException) {
            throw e
        } catch (_: Exception) {
            // голос не критичен: локальная копия всё равно останется
        }
        journalOwn(session, chatId, record)
    }

    // стоп опроса: событие от автора — после него голосование выключено
    // у всех; локальная запись события держит «закрыт» и у отправителя
    fun stopPoll(session: SessionInfo, chatId: String, pollRef: String) {
        val payload = chat.client.domain.VoteFormat.encodeStop(pollRef)
        val group = container.groups.byId(chatId)
        val record = if (group != null) {
            group.wrap(payload)
        } else {
            payload
        }
        try {
            if (group != null) {
                sendGroupEnvelope(session, chatId, record, null)
            } else {
                sendPeer(session, chatId, payload)
            }
        } catch (e: KeyChangedException) {
            throw e
        } catch (_: Exception) {
            // не критично: «закрыт» применится у получателей повтором
        }
        journalOwn(session, chatId, record)
    }

    // ─── состав группы (Фаза M1) ───

    // Служебное сообщение об изменении состава: конверт несёт НОВЫЙ состав,
    // fan-out — по составу + исключённым (чтобы исключённый узнал).
    // Отправка мгновенная, как у реакций. Офлайн-потеря некритична:
    // состав есть в каждом следующем конверте, дрейн применит его сам.
    // ponytail: полный офлайн-гарант — прогон через outbox с extraTargets.
    fun sendGroupChange(session: SessionInfo, chatId: String, added: List<String>, removed: List<String>) {
        val group = container.groups.byId(chatId) ?: return
        val payload = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(session.login, added, removed),
        )
        val wrapped = group.wrap(payload)
        try {
            sendGroupEnvelope(session, chatId, wrapped, (group.members + removed).distinct())
        } catch (_: Exception) {
            // некритично: состав применится со следующим сообщением;
            // смену ключей пира покажет штатный TOFU-баннер в диалоге
        }
        session.store.appendMonotonic(chatId, session.login, wrapped)
    }

    // Переименование группы (Фаза N3): локально новое имя + служебная
    // rn-запись. 1.4: мутатор идёт через outbox как управляющая запись —
    // офлайн-участник получит его при появлении сети, а не никогда;
    // имя, кроме того, едет в каждом конверте (пропущенный rn лечится
    // следующим сообщением с новее ревизией)
    fun sendGroupRename(session: SessionInfo, chatId: String, newName: String) {
        container.groups.rename(chatId, newName)
        val renamed = container.groups.byId(chatId) ?: return
        val payload = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(session.login, emptyList(), emptyList(), renameTo = renamed.name),
        )
        container.outbox.addControl(chatId, payload, CONTROL_GROUP_MUTATOR, session.login)
        journalOwn(session, chatId, renamed.wrap(payload))
    }

    // ─── Фаза AB10 (аналог broadcast-каналов Telegram): режим «только
    // владелец пишет» — mc-запись всем, как переименование ───

    // включить/выключить режим канала: владелец — включивший (его логин);
    // выключить может только текущий владелец (гейт в VM/UI)
    fun sendGroupBroadcast(session: SessionInfo, chatId: String, on: Boolean) {
        val owner = if (on) session.login else null
        container.groups.setBroadcast(chatId, owner)
        val group = container.groups.byId(chatId) ?: return
        val payload = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(
                session.login, emptyList(), emptyList(),
                broadcast = if (on) session.login else "",
            ),
        )
        // 1.4: управляющая запись outbox с повторами по участникам
        container.outbox.addControl(chatId, payload, CONTROL_GROUP_MUTATOR, session.login)
        journalOwn(session, chatId, group.wrap(payload))
    }

    // ─── Фаза AC3 (аналог About/Description у Telegram): описание группы —
    // mc-запись всем + поле gd в каждом конверте, как имя ───

    // описание меняет любой участник (как переименование N3);
    // пустая строка — снятие описания
    fun sendGroupDescription(session: SessionInfo, chatId: String, desc: String) {
        container.groups.setDescription(chatId, desc)
        val group = container.groups.byId(chatId) ?: return
        val payload = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(
                session.login, emptyList(), emptyList(), descTo = group.desc,
            ),
        )
        // 1.4: управляющая запись outbox с повторами по участникам
        container.outbox.addControl(chatId, payload, CONTROL_GROUP_MUTATOR, session.login)
        journalOwn(session, chatId, group.wrap(payload))
    }

    // ─── Фаза AC4 (аналог slow mode у Telegram): период между сообщениями —
    // mc-запись всем + поле sm в каждом конверте ───

    // период включает/выключает любой участник (у нас нет админов);
    // 0 — выключить
    fun sendGroupSlowMode(session: SessionInfo, chatId: String, periodSec: Int) {
        if (!chat.client.domain.SlowMode.validPeriod(periodSec)) return
        container.groups.setSlowMode(chatId, periodSec)
        val group = container.groups.byId(chatId) ?: return
        val payload = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(
                session.login, emptyList(), emptyList(), slowModeTo = group.slowMode,
            ),
        )
        // 1.4: управляющая запись outbox с повторами по участникам
        container.outbox.addControl(chatId, payload, CONTROL_GROUP_MUTATOR, session.login)
        journalOwn(session, chatId, group.wrap(payload))
    }

    // ─── Фаза AD6 (аналог «Restrict saving content» у Telegram): запрет
    // пересылки и сохранения содержимого группы — mc-запись всем,
    // как переименованию; включает/выключает любой участник ───

    fun sendGroupNoForward(session: SessionInfo, chatId: String, on: Boolean) {
        container.groups.setNoForward(chatId, on)
        val group = container.groups.byId(chatId) ?: return
        val payload = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(
                session.login, emptyList(), emptyList(), noForwardTo = on,
            ),
        )
        // 1.4: управляющая запись outbox с повторами по участникам
        container.outbox.addControl(chatId, payload, CONTROL_GROUP_MUTATOR, session.login)
        journalOwn(session, chatId, group.wrap(payload))
    }

    // Фаза AJ (аналог Allowed Reactions: none у Telegram): запрет
    // реакций — mc-запись всем + поле nr в каждом конверте
    fun sendGroupNoReactions(session: SessionInfo, chatId: String, on: Boolean) {
        container.groups.setNoReactions(chatId, on)
        val group = container.groups.byId(chatId) ?: return
        val payload = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(
                session.login, emptyList(), emptyList(), noReactionsTo = on,
            ),
        )
        container.outbox.addControl(chatId, payload, CONTROL_GROUP_MUTATOR, session.login)
        journalOwn(session, chatId, group.wrap(payload))
    }

    // ─── AI3 (аналог admin rights у Telegram): администраторы группы —
    // mc-запись всем + поле ad в каждом конверте, как остальным флагам.
    // Права админа: удаление чужих сообщений; назначение — как
    // остальные настройки (переименование/описание): любой участник ───

    fun sendGroupAdmins(session: SessionInfo, chatId: String, admins: List<String>) {
        container.groups.setAdmins(chatId, admins)
        val group = container.groups.byId(chatId) ?: return
        val payload = SystemFormat.encodeChange(
            chat.client.domain.GroupChange(
                session.login, emptyList(), emptyList(), adminsTo = group.admins,
            ),
        )
        // 1.4: управляющая запись outbox с повторами по участникам
        container.outbox.addControl(chatId, payload, CONTROL_GROUP_MUTATOR, session.login)
        journalOwn(session, chatId, group.wrap(payload))
    }

    // конверт сам является записью об изменении состава (Фаза M1)
    private fun isGroupChangeEnvelope(inner: MessageCodec.Kind): Boolean =
        inner is MessageCodec.Kind.SystemInfo && inner.change != null

    // «тихое» внутреннее содержимое группы (A3): реакция или служебная
    // запись — не новое сообщение, уведомление не дёргаем; голос в опросе
    // (Фаза W1) — тоже тихий, как реакция
    private fun isQuietGroupInner(inner: MessageCodec.Kind, content: ByteArray): Boolean {
        if (inner is MessageCodec.Kind.SystemInfo) return true
        val envelope = chat.client.domain.Envelope.decode(
            chat.client.domain.MessageCodec.innerContent(content),
        ) ?: return false
        return envelope.type == chat.client.domain.Envelope.TYPE_REACTION ||
            envelope.type == chat.client.domain.Envelope.TYPE_VOTE
    }

    // ─── удаление ───

    // удаляет запись по ref (или ts для старых) + реакции на неё + таймер
    fun deleteMessage(
        session: SessionInfo,
        context: Context,
        chatId: String,
        ref: String,
        ts: Long,
        window: Int = 2000, // окно поиска цели и реакций (C1)
    ) {
        val history = session.store.history(chatId, window)
        val target = history.firstOrNull { record ->
            MessageRef.of(record.content) == ref || record.timestamp == ts
        } ?: return
        // реакции на цель: по ref, старые — по ts; голоса в опросе — тоже (W1)
        for (record in history) {
            val parsed = Session.parseRecord(record, container.appPrefs.legacyDecodeSince())
            when (parsed) {
                is MessageCodec.Parsed.ReactionOnly -> {
                    val r = parsed.reaction
                    if ((r.targetRef != null && MessageRef.sameRef(r.targetRef, ref)) ||
                        (r.targetRef == null && r.targetTs == target.timestamp)
                    ) {
                        session.store.deleteRecord(chatId, record.timestamp, record.content)
                    }
                }
                // голоса на удалённый опрос умирают вместе с ним (W1)
                is MessageCodec.Parsed.VoteOnly -> {
                    if (parsed.vote.targetRef?.let { MessageRef.sameRef(it, ref) } == true) {
                        session.store.deleteRecord(chatId, record.timestamp, record.content)
                    }
                }
                else -> {}
            }
        }
        // закрепы на удалённое сообщение снимаем (Фаза W5: список)
        if (container.chatPrefs.pinnedMessages(chatId).any { MessageRef.sameRef(it.first, ref) }) {
            container.chatPrefs.removePinnedMessage(chatId, ref)
        }
        session.store.deleteRecord(chatId, target.timestamp, target.content)
        container.destruct.remove(chatId, target.timestamp)
        // локальная копия медиа больше не нужна, если на неё нет ссылок
        val info = (Session.parseRecord(target, container.appPrefs.legacyDecodeSince())
            as? MessageCodec.Parsed.Content)?.kind as? MessageCodec.Kind.Media
        if (info != null) container.media.deleteIfUnused(session, info.info.id)
    }

    // ─── «удалить у всех» (Фаза P1, аналог revoke в Telegram) ───

    // применение входящего события: цель — только запись автора кадра (A4).
    // Чистка — как у локального удаления: реакции, закреп, таймер, медиа.
    // Цели нет (история стёрта/разная) — событие просто игнорируется
    private fun applyIncomingDelete(
        session: SessionInfo,
        context: Context,
        chatId: String,
        author: String,
        del: chat.client.domain.DeleteFormat.Decoded,
        // AI3: админы группы могут удалять чужие сообщения; пусто в 1:1
        admins: Set<String> = emptySet(),
    ): Boolean {
        // цель ищем ДО удаления: по ней чистим закреп/таймер/медиа
        val target = chat.client.domain.MessagePin.resolve(
            session.store, chatId, del.targetRef, del.targetTs,
        ) ?: return false
        // цель — запись автора кадра (A4); админ сносит и чужое (AI3)
        if (target.from != author && author !in admins) return false
        val ref = MessageRef.of(target.content)
        val media = (Session.parseRecord(target, container.appPrefs.legacyDecodeSince())
            as? MessageCodec.Parsed.Content)?.kind as? MessageCodec.Kind.Media
        if (!chat.client.domain.MessageDelete.apply(
                session.store, chatId, author, del.targetRef, del.targetTs, admins = admins,
            )
        ) {
            return false
        }
        // закрепы на удалённое сообщение снимаем (Фаза W5: список)
        if (container.chatPrefs.pinnedMessages(chatId).any { MessageRef.sameRef(it.first, ref) }) {
            container.chatPrefs.removePinnedMessage(chatId, ref)
        }
        container.destruct.remove(chatId, target.timestamp)
        // локальная копия медиа больше не нужна, если на неё нет ссылок
        if (media != null) container.media.deleteIfUnused(session, media.info.id)
        return true
    }

    // удаление у всех со своей стороны: событие ставится в outbox и уходит
    // участникам с повторами (А3) — прежде при офлайне части участников
    // событие терялось молча. Локально запись убирается ТОЛЬКО после
    // успешной постановки в очередь. Фолбэк по ts — для старых записей.
    // AI3: админ группы может удалять и ЧУЖИЕ сообщения (asAdmin — гейт VM)
    @Throws(KeyChangedException::class)
    fun deleteForEveryone(
        session: SessionInfo,
        context: Context,
        chatId: String,
        targetRef: String,
        targetTs: Long,
        window: Int = 2000, // окно поиска цели (C1)
        asAdmin: Boolean = false,
    ): Boolean {
        val history = session.store.history(chatId, window).sortedBy { it.timestamp }
        val target = history.firstOrNull { MessageRef.matches(targetRef, it.content) }
            ?: history.firstOrNull { it.timestamp == targetTs }
            ?: return false
        // у всех можно удалить СВОЁ (A4); чужое — только админу группы (AI3)
        if (target.from != session.login && !asAdmin) return false
        val event = chat.client.domain.DeleteFormat.encode(MessageRef.of(target.content), target.timestamp)
        container.outbox.addControl(chatId, event, CONTROL_DELETE, session.login)
        deleteMessage(session, context, chatId, MessageRef.of(target.content), target.timestamp, window)
        return true
    }

    // ─── правка сообщений ───

    // Окно правки ограничивает отправитель (48 ч в VM); получатель применяет
    // событие как есть. Новый контент собирается один раз и хранится
    // байт-в-байт у всех сторон — ref совпадает для реакций и ответов.
    // У группы конверт цели берём из самой цели: состав оригинала, без
    // побочных изменений членства.
    @Throws(KeyChangedException::class)
    fun editMessage(
        session: SessionInfo,
        chatId: String,
        targetRef: String,
        targetTs: Long,
        newText: String,
        window: Int = 2000, // окно поиска цели правки (C1)
    ) {
        val history = session.store.history(chatId, window).sortedBy { it.timestamp }
        val target = history.firstOrNull { MessageRef.matches(targetRef, it.content) }
            ?: history.firstOrNull { it.timestamp == targetTs }
            ?: throw IllegalArgumentException("цель правки не найдена")
        val editedInner = chat.client.domain.Envelope.encodeBytes(
            chat.client.domain.Envelope.TYPE_EDITED,
            newText.encodeToByteArray(),
        )
        val groupEnvelope = chat.client.domain.Envelope.decode(target.content)
        val targetGroup = if (groupEnvelope?.type == chat.client.domain.Envelope.TYPE_GROUP) {
            chat.client.domain.GroupFormat.decodeV2(groupEnvelope.payload)
        } else {
            null
        }
        // состав — из оригинала (без сайд-эффектов, G3), а служебные
        // флаги (bc/gd/sm/nf) — из известного состояния группы: правка
        // не должна рассылать устаревшее отсутствие флагов (фикс AD6,
        // по образцу исправления AC про потерю режима канала)
        val knownGroup = container.groups.byId(chatId)
        val group = targetGroup?.let {
            chat.client.domain.GroupChat(
                it.group.id, it.group.name, it.group.members, it.group.rev,
                knownGroup?.broadcast, knownGroup?.desc ?: "",
                knownGroup?.slowMode ?: 0, knownGroup?.noForward ?: false,
                knownGroup?.admins ?: emptyList(), knownGroup?.noReactions ?: false,
            )
        } ?: knownGroup
        val newContent = if (group != null) {
            group.wrap(editedInner)
        } else {
            editedInner
        }
        // GH1: в событие правки вкладываем предыдущую версию — у получателя
        // соберётся история правок
        val event = chat.client.domain.EditFormat.encode(targetRef, target.timestamp, newContent, target.content)
        // правка уходит через outbox с повторами (А3): офлайн-участник
        // получит её позже, а не никогда; локально применяется после
        // успешной постановки в очередь
        container.outbox.addControl(chatId, event, CONTROL_EDIT, session.login)
        chat.client.domain.MessageEdit.apply(session.store, chatId, session.login, targetRef, target.timestamp, newContent, window)
        repointPinAfterEdit(chatId, targetRef, target.timestamp, newContent)
    }

    // ─── закреплённые сообщения (Фаза I) ───

    // применение события закрепа: цель ищем в журнале; ref надёжнее ts.
    // Фаза W5: список закрепов — p=1 добавляет один, p=0 снимает конкретный.
    // Цели нет (история стёрта/разная) — событие игнорируем
    // GH5: автоответчик — решает по чистой логике AutoReply и шлёт текст в outbox
    private fun maybeAutoReply(session: SessionInfo, chatId: String, incomingText: String) {
        val prefs = container.appPrefs
        if (!prefs.autoReplyEnabled()) return
        val text = prefs.autoReplyText()
        if (text.isBlank()) return
        val now = container.clock.now()
        val nowMin = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .let { it.hour * 60 + it.minute }
        // B8: не отвечаем в мьюте/заблокированным, на старый бэклог и на
        // собственный текст автоответа (иначе петля двух автоответчиков)
        if (!chat.client.domain.AutoReply.shouldReply(
                chat.client.domain.AutoReply.Input(
                    enabled = true,
                    nowMin = nowMin,
                    startMin = prefs.quietFromMin(),
                    endMin = prefs.quietToMin(),
                    lastSentAtSec = prefs.autoReplyLastAt(chatId),
                    nowSec = now / 1000,
                    // ts входящего здесь недоступен (WalMessage без метки) — возраст не проверяем
                    messageTsSec = 0L,
                    chatMuted = container.chatPrefs.muted(chatId),
                    senderBlocked = container.blockedSenders.isBlocked(chatId),
                    isSelfChat = chatId == session.login,
                    textEqualsAutoReply = incomingText.trim() == text.trim(),
                ),
            )
        ) return
        val payload = chat.client.domain.Envelope.encode(chat.client.domain.Envelope.TYPE_TEXT, text)
        container.outbox.add(chatId, payload, text.take(40), login = session.login)
        prefs.setAutoReplyLastAt(chatId, now / 1000)
    }

    private fun applyPin(session: SessionInfo, chatId: String, pin: chat.client.domain.PinFormat.Decoded) {
        val target = chat.client.domain.MessagePin.resolve(
            session.store, chatId, pin.targetRef, pin.targetTs,
        ) ?: return
        val ref = MessageRef.of(target.content)
        if (pin.pinned) container.chatPrefs.addPinnedMessage(chatId, ref, target.timestamp)
        else container.chatPrefs.removePinnedMessage(chatId, ref)
    }

    // закреп/откреп со своей стороны: событие в outbox с повторами (А3),
    // локальное состояние — сразу после постановки в очередь
    @Throws(KeyChangedException::class)
    fun setPinned(
        session: SessionInfo,
        chatId: String,
        targetRef: String,
        targetTs: Long,
        pinned: Boolean,
        window: Int = 2000, // окно поиска цели закрепа (C1)
    ) {
        val event = chat.client.domain.PinFormat.encode(targetRef, targetTs, pinned)
        container.outbox.addControl(chatId, event, CONTROL_PIN, session.login)
        if (pinned) container.chatPrefs.addPinnedMessage(chatId, targetRef, targetTs)
        else container.chatPrefs.removePinnedMessage(chatId, targetRef)
    }

    // правка меняет ref цели: переносим закреп на новый ref (Фаза W5 —
    // переносим ВСЕ закрепы, ссылающиеся на цель, в списке)
    private fun repointPinAfterEdit(chatId: String, targetRef: String?, targetTs: Long?, newContent: ByteArray) {
        if (targetRef == null) return
        val list = container.chatPrefs.pinnedMessages(chatId)
        if (list.any { MessageRef.sameRef(it.first, targetRef) }) {
            container.chatPrefs.setPinnedMessages(
                chatId,
                chat.client.domain.PinnedMessages.repoint(
                    list, targetRef, MessageRef.of(newContent), targetTs,
                ),
            )
        }
    }

    // ─── пересылка ───

    // Чистая логика — в domain.MessageForward (A8), тестируется на JVM
    fun forwardPayload(content: ByteArray, author: String? = null, keepAuthor: Boolean = true): ByteArray? =
        chat.client.domain.MessageForward.payload(content, author, keepAuthor)

    // ─── перенос истории ───

    // шлёт историю чанками с прогрессом; возвращает число записей.
    // 4.3: чанки собираются и отправляются ПО ЧАТАМ — прежняя полная
    // сборка всей истории в память валила перенос больших устройств;
    // прогрессу нужен общий счёт — дешёвый проход подсчёта перед отправкой
    fun transferHistory(
        session: SessionInfo,
        targetDevice: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val targetPub = session.api.peerKeys(session.login)
            .firstOrNull { it.first == targetDevice }?.second
            ?: throw RelayException(404, "устройство $targetDevice не привязано к ${session.login}")
        val chatIds = session.store.chatIds()
        var total = 0
        for (chatId in chatIds) {
            total += session.store.history(chatId, Int.MAX_VALUE).size
        }
        var done = 0
        for (chatId in chatIds) {
            val items = ArrayList<TransferCodec.Item>()
            for (record in session.store.history(chatId, Int.MAX_VALUE)) {
                items += TransferCodec.Item(chatId, record.from, record.timestamp, record.content)
            }
            for (chunk in TransferCodec.chunk(items)) {
                val blob = session.e2e.seal(targetPub, TransferCodec.encodeChunk(chunk))
                session.api.send(session.token, session.login, "transfer", listOf(targetDevice to blob))
                done += chunk.size
                onProgress(minOf(done, total), total)
            }
        }
        return total
    }

    // уведомление остальных устройств о новом привязанном
    fun notifyNewDevice(session: SessionInfo, newDeviceId: String, fingerprint: String) {
        try {
            val payload = chat.client.domain.DeviceFormat.encode(newDeviceId, fingerprint)
            val devices = session.api.peerKeys(session.login)
                .filter { it.first != session.deviceId && it.first != newDeviceId }
            if (devices.isNotEmpty()) {
                session.api.send(session.token, session.login, "chat", session.sealFor(devices, payload))
            }
        } catch (e: Exception) {
            Log.w(TAG, "не удалось уведомить устройства о привязке", e)
        }
    }

    companion object {
        private const val KEY_TTL_MS = 60_000L
        private const val DEDUP_TAIL = 512 // хвост истории для посева dedup
        private const val DRAIN_MAX_PAGES = 1024 // потолок страниц дрейна (страховка)
        private const val CALL_RECORD_SCAN = 512 // хвост поиска записи звонка по callId (GA)
        // AL9: хвост журнала, где ищем цель реакции — глубже и не бывает нужно
        private const val REACTION_SCAN_TAIL = 512

        // виды управляющих событий outbox (А3)
        const val CONTROL_EDIT = "edit"
        const val CONTROL_DELETE = "delete"
        const val CONTROL_PIN = "pin"
        const val CONTROL_GROUP_MUTATOR = "group_mutator" // 1.4: rn/bc/gd/sm/nf

        // лимиты карантина на группу (A3): спам приглашениями не может
        // заполнить диск и список
        const val QUARANTINE_MAX_FILES = 50
        const val QUARANTINE_MAX_BYTES = 1024 * 1024L
    }
}
