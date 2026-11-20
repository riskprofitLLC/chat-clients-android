package chat.client.data

import android.content.Context
import android.util.Log
import chat.client.domain.MessageCodec
import chat.client.domain.wrap
import chat.client.net.RelayException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64

// Персистентный outbox: исходящие переживают убийство процесса и уходят
// при появлении сети. Правила повтора (A4/A5):
// - перманентная ошибка участника — только 404 (не зарегистрирован);
// - IO/5xx/429 — запись остаётся в очереди с бэкоффом и лимитом попыток;
// - записи привязаны к логину: при смене аккаунта чистятся только
//   ЧУЖИЕ, свои ждут повторного входа и сети (A2);
// - flush одиночный (Mutex): параллельные вызовы не дублируют отправку;
// - бэкофф и счётчик попыток ставятся ТОЛЬКО в flushAll (A2): flushOne
//   обновляет прогресс/payload и бросает исключения — устаревший
//   снимок записи больше не может откатить свежее состояние.
class Outbox(private val container: AppContainer) {

    class Entry(
        val id: String,
        val chatId: String,
        val login: String,
        val payload: ByteArray?, // null, пока вложение не загружено (staged)
        val preview: String,
        val replyAuthor: String?,
        val replySnippet: String?,
        val replyRef: String?,
        val replyTargetTs: Long?,
        val createdAt: Long,
        val attempts: Int,
        val nextAttemptAt: Long, // не раньше этого момента (бэкофф)
        val deliveredMembers: Set<String>,
        val failedMembers: Set<String>,
        val permanentFailed: Boolean, // 404/413 у 1:1 — ручной повтор
        val staged: StagedAttachment?,
        val wrapped: ByteArray? = null, // групповой конверт, собранный ОДИН раз (A2)
        val groupProgress: String = "", // «3/5» для группового повтора
        val sendAt: Long = 0L, // 0 — сразу; иначе момент отложенной отправки
        // Фаза AJ: отправить, когда собеседник появится в сети (1:1);
        // держим в очереди, опрос присутствия — в цикле flushAll
        val whenOnline: Boolean = false,
        val control: String = "", // А3: правка/удаление у всех/закреп — без журнала и без пузыря в UI
        val syncPending: Boolean = false, // A2: копия на свои устройства ещё не ушла
        val authHold: Boolean = false, // A5: пауза до повторного входа (401/403)
    )

    // Вложение, ожидающее загрузки: файл зашифрован контент-ключом
    // локально (A3), реальный id появляется после upload.
    class StagedAttachment(
        val fileName: String, // внутри dir/att
        val keyB64: String, // контент-ключ шифрования блоба
        val descriptor: String, // JSON полей MediaInfo без id/key
    )

    private val context: Context = container.app
    private val dir = File(context.filesDir, "outbox")
    private val attDir = File(dir, "att")

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    // A5: сервер отверг авторизацию — очередь на паузе до повторного входа
    // тем же аккаунтом. Сигнал собирает AppViewModel и сбрасывает сессию:
    // иначе приложение молча оставалось с мёртвым токеном
    private val _authFailed = MutableStateFlow<String?>(null)
    val authFailed: StateFlow<String?> = _authFailed.asStateFlow()

    // Фаза AJ: процент выгрузки вложения по id записи (runtime, без диска);
    // отсутствие ключа — не в выгрузке
    private val _uploadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val uploadProgress: StateFlow<Map<String, Int>> = _uploadProgress.asStateFlow()

    // single-flight: параллельные flushAll сливаются в один прогон (A5)
    private val flushMutex = Mutex()

    // A5: сервер отверг авторизацию — очередь на паузе до повторного входа
    // тем же аккаунтом; VM/сервис слушают и ведут пользователя на вход
    // A5: 401/403 — не «временный сбой», пауза без сжигания попыток
    private class AuthHoldException : Exception("auth_hold")

    // загрузка с диска — один раз на процесс (A2): повторные вызовы из
    // воркера/шеринга/уведомлений не воскрешают удалённые записи
    private val warmupLock = Any()
    @Volatile private var warmedUp = false

    // tombstone удалённых записей (A2): защищают окно между remove()
    // и (единственным) warmup в свежем процессе
    private val removedIds = HashSet<String>()

    // ─── добавление ───

    fun add(
        chatId: String,
        payload: ByteArray,
        preview: String,
        replyAuthor: String? = null,
        replySnippet: String? = null,
        replyRef: String? = null,
        replyTargetTs: Long? = null,
        login: String,
        sendAt: Long = 0L,
        whenOnline: Boolean = false, // Фаза AJ
    ): Entry? = addInner(chatId, payload, null, preview, replyAuthor, replySnippet, replyRef, replyTargetTs, login, sendAt, whenOnline)

    // запись с вложением: файл уже зашифрован контент-ключом (A3)
    @Throws(IOException::class)
    fun addAttachment(
        chatId: String,
        encrypted: ByteArray, // iv(12) + AES-GCM(данные)
        descriptor: JSONObject, // поля MediaInfo без id/key
        key: ByteArray,
        preview: String,
        login: String,
    ): Entry? {
        attDir.mkdirs()
        val attId = "a" + System.nanoTime()
        val attFile = File(attDir, "$attId.bin")
        val tmp = File(attDir, "$attId.tmp")
        tmp.writeBytes(encrypted)
        check(tmp.renameTo(attFile)) { "не удалось сохранить вложение в очередь" }
        val staged = StagedAttachment(
            "$attId.bin",
            Base64.getEncoder().encodeToString(key),
            descriptor.toString(),
        )
        return addInner(chatId, null, staged, preview, null, null, null, null, login)
    }

    private fun addInner(
        chatId: String,
        payload: ByteArray?,
        staged: StagedAttachment?,
        preview: String,
        replyAuthor: String?,
        replySnippet: String?,
        replyRef: String?,
        replyTargetTs: Long?,
        login: String,
        sendAt: Long = 0L,
        whenOnline: Boolean = false,
        control: String = "",
    ): Entry? {
        // 3.3: единая точка гейта группы — режим канала и медленный режим
        // прежде жили только в ViewModel: быстрый ответ из уведомления и
        // ShareActivity их обходили. null — отправка отклонена
        if (control.isEmpty() && !sendAllowed(chatId, login, sendAt, payload)) return null
        // групповой конверт собираем ОДИН раз при постановке в очередь (A2):
        // один и тот же ref уходит всем участникам и в журнал, реакции и
        // ответы сходятся даже после повторов и рестартов
        val wrapped = payload?.let { p -> wrapForGroup(chatId, p) }
        val entry = Entry(
            id = "o" + System.nanoTime(),
            chatId = chatId,
            login = login,
            payload = payload,
            preview = preview,
            replyAuthor = replyAuthor,
            replySnippet = replySnippet,
            replyRef = replyRef,
            replyTargetTs = replyTargetTs,
            createdAt = container.clock.now(),
            attempts = 0,
            nextAttemptAt = 0L,
            deliveredMembers = emptySet(),
            failedMembers = emptySet(),
            permanentFailed = false,
            staged = staged,
            wrapped = wrapped,
            sendAt = sendAt,
            whenOnline = whenOnline,
            control = control,
        )
        _entries.update { it + entry }
        persist(entry)
        return entry
    }

    // 3.3: гейт отправки группы в единой точке очереди: канал (AB10) и
    // медленный режим (AC4). Отложенная отправка и живая геопозиция
    // (не сообщение пользователя) медленный режим обходит
    private fun sendAllowed(chatId: String, login: String, sendAt: Long, payload: ByteArray?): Boolean {
        val group = container.groups.byId(chatId) ?: return true
        if (payload != null &&
            chat.client.domain.Envelope.decode(payload)?.type == chat.client.domain.Envelope.TYPE_GEO
        ) {
            return true
        }
        if (!chat.client.domain.GroupRules.canWrite(group, login)) return false
        if (group.slowMode <= 0 || sendAt > 0L) return true
        // от последнего СВОЕГО содержательного сообщения (как в VM);
        // глубже окна — считаем «не писал», режим не блокирует
        val legacySince = container.appPrefs.legacyDecodeSince()
        val last = container.storeGate.history(chatId, 200).lastOrNull {
            it.from == login &&
                (Session.parseRecord(it, legacySince) as? MessageCodec.Parsed.Content)
                    ?.kind !is MessageCodec.Kind.SystemInfo
        }?.timestamp ?: 0L
        return chat.client.domain.SlowMode.remainingSec(last, container.clock.now(), group.slowMode) == null
    }

    // управляющее событие (А3: правка/удаление у всех/закреп): уходит
    // участникам с повторами, в журнал не пишется — локальный эффект
    // применяет постановщик сразу после успешной записи в очередь
    fun addControl(chatId: String, event: ByteArray, control: String, login: String): Entry? =
        addInner(chatId, event, null, "", null, null, null, null, login, control = control)

    // конверт группы для payload; null — чат не группа.
    // 1.4: единый wrap — прежняя копия 9-аргументного вызова жила
    // отдельной жизнью и рассыпалась при добавлении полей группы
    private fun wrapForGroup(chatId: String, payload: ByteArray): ByteArray? {
        val group = container.groups.byId(chatId) ?: return null
        return group.wrap(payload)
    }

    // только «настоящие» исходящие: управляющие события (А3) в списке
    // отправляемых не показываются
    fun pendingFor(chatId: String, login: String? = null): List<Entry> =
        _entries.value.filter {
            it.chatId == chatId && it.control.isEmpty() && (login == null || it.login == login)
        }

    fun hasPending(chatId: String): Boolean = _entries.value.any { it.chatId == chatId }

    // Фаза Z2 (аналог Scheduled Messages): отложенные этого чата —
    // только «настоящие» сообщения, по возрастанию времени отправки
    fun scheduledFor(chatId: String): List<Entry> =
        _entries.value.filter { it.chatId == chatId && (it.sendAt > 0 || it.whenOnline) && it.control.isEmpty() }
            .sortedBy { it.sendAt }

    // ближайший момент отложенной отправки этого логина (для будильника)
    fun nextDueSendAt(login: String): Long? =
        _entries.value.filter { it.login == login && it.sendAt > 0 }.minOfOrNull { it.sendAt }

    // ручной повтор: сбрасываем бэкофф и перманентный флаг (A4)
    fun resetForRetry(id: String) {
        update(id) { it.copy2(attempts = 0, nextAttemptAt = 0L, permanentFailed = false) }
    }

    // Фаза AB6: правка отложенной записи — новый текст (готовый конверт)
    // и/или новое время; будильник пересчитывается на ближайший срок
    fun updateScheduled(id: String, payload: ByteArray?, preview: String, sendAt: Long): Entry? {
        val updated = update(id) {
            it.copy2(payload = payload, preview = preview, sendAt = sendAt, whenOnline = false)
        }
        if (updated != null) {
            try {
                nextDueSendAt(updated.login)?.let { due ->
                    chat.client.service.BackgroundSyncWorker.ensureWake(
                        context, Schedule.delayUntil(due, container.clock.now()),
                    )
                }
            } catch (_: Exception) {
            }
        }
        return updated
    }

    fun remove(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        _entries.update { list -> list.filterNot { it.id == id } }
        // A5: tombstone нужен только ДО единственного warmup — потом он
        // уже не воскресит ничего, а набор рос вечно
        synchronized(warmupLock) {
            if (!warmedUp) removedIds += id
        }
        entry.staged?.let { File(attDir, it.fileName).delete() }
        File(dir, "${entry.id}.box").delete()
    }

    fun dropAllFor(chatId: String) {
        _entries.value.filter { it.chatId == chatId }.forEach { remove(it.id) }
    }

    // обновление по id: муторка применяется к ТЕКУЩЕМУ состоянию из
    // _entries (A2), а не к устаревшему снимку вызывающего; persist —
    // ВНУТРИ атомарного update, иначе две параллельные правки могли
    // записать на диск устаревшее состояние последним. Записи нет —
    // no-op (удалена посреди отправки)
    private fun update(id: String, mutate: (Entry) -> Entry): Entry? {
        var updated: Entry? = null
        _entries.update { list ->
            var found = false
            val out = list.map {
                if (it.id == id) {
                    found = true
                    mutate(it).also { u ->
                        updated = u
                        persist(u)
                    }
                } else {
                    it
                }
            }
            if (found) out else list
        }
        return updated
    }

    private fun current(id: String): Entry? = _entries.value.firstOrNull { it.id == id }

    // ─── прогон очереди ───

    // true — хотя бы одно ушло в журнал. Ошибка ОДНОЙ записи не рвёт
    // очередь (A2): IO — бэкофф этой записи, KeyChanged — запись ждёт
    // подтверждения пользователя (до минуты), остальные идут дальше.
    // Бэкофф/счётчик ставятся только здесь (A2)
    suspend fun flushAll(session: SessionInfo): Int = flushMutex.withLock {
        var completed = 0
        val now = container.clock.now()
        // A5: вход тем же аккаунтом снимает паузу 401/403 — очередь доигрывает
        for (entry in _entries.value.filter { it.login == session.login && it.authHold }) {
            update(entry.id) { it.copy2(authHold = false) }
        }
        if (_authFailed.value == session.login) _authFailed.value = null
        // Фаза AJ: «когда появится в сети» — опрос присутствия цели,
        // один запрос на чат за цикл; ошибка опроса — «не в сети»,
        // следующий цикл попробует снова
        val onlineTargets = HashMap<String, Boolean>()
        suspend fun targetOnline(chatId: String): Boolean = onlineTargets.getOrPut(chatId) {
            if (container.groups.byId(chatId) != null) {
                false // группы не опрашиваем: вариант только для 1:1
            } else {
                try {
                    session.api.checkOnline(session.token, chatId)
                        .getOrNull()?.online == true
                } catch (_: Exception) {
                    false
                }
            }
        }
        for (entryIn in _entries.value.toList()) {
            if (entryIn.login != session.login) continue
            if (entryIn.permanentFailed) continue
            if (entryIn.authHold) continue // A5: ждёт повторного входа
            if (entryIn.nextAttemptAt > now) continue
            // Фаза AJ: когда-online-запись дожидается живого собеседника;
            // как только онлайн — флаг снимается, отправка обычная
            if (entryIn.whenOnline) {
                if (!targetOnline(entryIn.chatId)) continue
                update(entryIn.id) { it.copy2(whenOnline = false) }
            }
            if (!Schedule.isDue(entryIn.sendAt, now)) continue // отложенные ждут своего часа
            val id = entryIn.id
            try {
                if (flushOne(session, entryIn)) {
                    completed++
                } else {
                    // частичная доставка группы / перманентный отказ:
                    // попытка засчитывается, бэкофф ставится здесь же (A2)
                    markAttemptFailed(id)
                }
            } catch (e: KeyChangedException) {
                // смена ключей блокирует ТОЛЬКО эту запись (A2): частичный
                // прогресс доставленных участников сохраняем, после
                // accept они не получат копию
                applyPartialDelivery(id, e.partialDelivered)
                update(id) { it.copy2(nextAttemptAt = container.clock.now() + KEY_CHANGED_RETRY_MS) }
                Log.w("Outbox", "отправка в ${entryIn.chatId} заблокирована: ключи ${e.peer} изменились")
            } catch (_: AuthHoldException) {
                // A5: 401/403 — пауза без счётчика попыток, ждём повторного входа
                Log.w("Outbox", "отправка в ${entryIn.chatId} на паузе: авторизация отвергнута")
            } catch (e: Exception) {
                markAttemptFailed(id)
                Log.w("Outbox", "отправка в ${entryIn.chatId} отложена: ${e.message}")
            }
        }
        // отложенная отправка (A2): после прогона взводим будильник на
        // ближайшую оставшуюся запись этого логина
        val nextDue = _entries.value
            .filter { it.login == session.login && it.sendAt > 0 }
            .minOfOrNull { it.sendAt }
        if (nextDue != null) {
            try {
                chat.client.service.BackgroundSyncWorker.ensureWake(
                    context, Schedule.delayUntil(nextDue, container.clock.now()),
                )
            } catch (_: Exception) {
            }
        }
        completed
    }

    // одна запись: загрузка вложения → fan-out → журнал. Бросает
    // KeyChangedException (подтверждение) и IOException/RelayException
    // (временный сбой). Бэкофф НЕ ставит — это делает flushAll (A2).
    private suspend fun flushOne(session: SessionInfo, entryIn: Entry): Boolean {
        var entry = current(entryIn.id) ?: return false
        // ts фиксируем ДО отправки: кадр delivered успевает прийти раньше
        // записи в журнал, иначе ✓✓ не выставится никогда (A16)
        val sendTs = container.clock.now()

        // 1) вложение ещё не загружено: upload из staged (A3);
        // Фаза AJ: колбэк прогресса прокидывает процент в UI по id записи
        if (entry.staged != null && entry.payload == null) {
            val stagedInfo = entry.staged!!
            try {
                val payload = uploadStaged(session, stagedInfo, entry.id)
                // payload закрепляем ВМЕСТЕ со сбросом staged (A2): после
                // рестарта запись не зависает без файла — файл больше не нужен
                entry = update(entry.id) { e -> e.copy2(payload = payload, staged = null) } ?: return false
                File(attDir, stagedInfo.fileName).delete()
            } catch (e: KeyChangedException) {
                throw e
            } catch (e: RelayException) {
                if (e.code == 401 || e.code == 403) {
                    // A5: медиа-загрузка без авторизации — та же пауза
                    update(entry.id) { it.copy2(authHold = true) }
                    _authFailed.value = entry.login
                    throw AuthHoldException()
                }
                if (e.code == 413) {
                    update(entry.id) { it.copy2(permanentFailed = true) }
                    return false
                }
                throw IOException("upload: ${e.message}", e)
            } catch (e: Exception) {
                throw IOException("upload: ${e.message}", e)
            }
        }

        val group = container.groups.byId(entry.chatId)
        val payload = entry.payload ?: return false

        return if (group != null) {
            // конверт из Entry — собран один раз при постановке (A2);
            // для старых записей без wrapped собираем и закрепляем сейчас
            val wrapped = entry.wrapped ?: wrapForGroup(entry.chatId, payload)?.let {
                update(entry.id) { e -> e.copy2(wrapped = it) }
                it
            } ?: payload
            val targets = group.members.distinct().filter { it != session.login }
            val remaining = targets.filter { it !in entry.deliveredMembers && it !in entry.failedMembers }
            if (remaining.isEmpty()) {
                // A2: даже с полностью доставленной группой запись не
                // завершается, пока копия на свои устройства не ушла
                if (entry.syncPending) {
                    if (!container.messageRepo.retryGroupSync(session, entry.chatId, wrapped)) {
                        return false // синк не дошёл — прогон позже
                    }
                    update(entry.id) { it.copy2(syncPending = false) }
                }
                complete(session, entry, wrapped, sendTs)
                return true
            }
            // 3.4: 401/403 — пауза авторизации без счётчика попыток, как у 1:1;
            // прежде участник с такой ошибкой сжигал все попытки за 12 циклов
            val report = try {
                container.messageRepo.sendGroupEnvelope(session, entry.chatId, wrapped, remaining)
            } catch (e: RelayException) {
                if (e.code == 401 || e.code == 403) {
                    update(entry.id) { it.copy2(authHold = true) }
                    _authFailed.value = entry.login
                    throw AuthHoldException()
                }
                throw e
            }
            // доставлены ТОЛЬКО реально ушедшие (A2): retryMembers остаются
            // в очереди, временный сбой не считается доставкой.
            // Счётчик попыток НЕ трогаем — его ставит flushAll (A2)
            val delivered = entry.deliveredMembers + (remaining - report.failedMembers.toSet() - report.retryMembers.toSet())
            val failed = entry.failedMembers + report.failedMembers
            // A2: копия на свои устройства не дошла — запись не завершается
            val syncPending = entry.syncPending || report.syncPending
            val updated = update(entry.id) {
                it.copy2(
                    deliveredMembers = delivered, failedMembers = failed,
                    groupProgress = "${delivered.size}/${targets.size}",
                    syncPending = syncPending,
                )
            } ?: return false
            if (delivered.isEmpty() && failed.size >= targets.size && targets.isNotEmpty()) {
                // A5: «никому не доставлено» — честный Failed с кнопками,
                // а не запись в журнал как отправленная
                // 3.4: управляющее событие с перманентным отказом удаляется
                // сразу: его не видно в UI, копить вечно незачем
                if (entry.control.isNotEmpty()) {
                    remove(entry.id)
                    return false
                }
                update(entry.id) { it.copy2(permanentFailed = true) }
                return false
            }
            if (delivered.size + failed.size >= targets.size) {
                // A2: синк на свои устройства обязателен до завершения;
                // сбой — повтор позже, попытки не сгорают
                if (syncPending) {
                    if (!container.messageRepo.retryGroupSync(session, entry.chatId, wrapped)) {
                        return false
                    }
                    update(entry.id) { it.copy2(syncPending = false) }
                }
                complete(session, updated, wrapped, sendTs)
                true
            } else {
                // часть участников не доставлена: повтор позже (A4);
                // бэкофф ставит flushAll (false) (A2)
                false
            }
        } else {
            try {
                container.messageRepo.sendPayload(session, entry.chatId, payload)
                complete(session, entry, payload, sendTs)
                true
            } catch (e: KeyChangedException) {
                throw e
            } catch (e: RelayException) {
                if (e.code == 401 || e.code == 403) {
                    // A5: авторизация отвергнута — пауза без сжигания попыток
                    update(entry.id) { it.copy2(authHold = true) }
                    _authFailed.value = entry.login
                    throw AuthHoldException()
                }
                if (e.code == 404 || e.code == 413) {
                    // получатель не зарегистрирован / вложение велико: ручной
                    // повтор; управляющее событие с перманентным отказом —
                    // удаляем сразу (3.4), оно не видно в UI
                    if (entry.control.isNotEmpty()) {
                        remove(entry.id)
                        return false
                    }
                    update(entry.id) { it.copy2(permanentFailed = true) }
                    return false
                }
                throw e
            } catch (e: Exception) {
                throw IOException("send: ${e.message}", e)
            }
        }
    }

    // upload зашифрованного staged-файла: расшифровываем контент-ключом,
    // грузим на сервер, строим дескриптор с настоящим id. Блоб УЖЕ
    // зашифрован контент-ключом на стейдже (iv + AES-GCM) — сервер получает
    // opaque-блоб, расшифровка на месте только у получателя. Файл НЕ трогаем —
    // его удаляет flushOne после закрепления payload (A2).
    // (Fix Фазы U, найден живым smoke: прежняя расшифровка перед загрузкой
    // отдавала релею plaintext, и получатель не мог открыть вложение)
    private suspend fun uploadStaged(
        session: SessionInfo,
        staged: StagedAttachment,
        entryId: String? = null,
    ): ByteArray {
        val file = File(attDir, staged.fileName)
        val blob = file.readBytes()
        val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (entryId == null) {
                session.api.uploadMedia(session.token, blob)
            } else {
                try {
                    session.api.uploadMedia(session.token, blob) { percent ->
                        _uploadProgress.value = _uploadProgress.value + (entryId to percent)
                    }
                } finally {
                    _uploadProgress.value = _uploadProgress.value - entryId
                }
            }
        }
        // локальная копия у отправителя: расшифровываем здесь же, чтобы
        // своё вложение не качать из транзита
        val key = Base64.getDecoder().decode(staged.keyB64)
        val plain = chat.crypto.CryptoPrimitives.aesGcmDecrypt(
            key,
            blob.copyOfRange(0, 12),
            blob.copyOfRange(12, blob.size),
            ByteArray(0),
        )
        chat.client.media.Media.save(container.app, id, plain)
        val json = JSONObject(staged.descriptor).put("id", id)
            .put("key", staged.keyB64)
        val info = chat.client.domain.MediaJson.parseOrNull(json.toString())
            ?: throw IOException("некорректный дескриптор вложения")
        return chat.client.domain.MediaJson.message(info)
    }

    // временный сбой: бэкофф и лимит попыток — по ТЕКУЩЕМУ состоянию (A4/A2).
    // Управляющее событие с исчерпанными попытками убираем (А3): локальный
    // эффект уже применён, копить его вечно в очереди незачем
    private fun markAttemptFailed(id: String) {
        val cur = current(id)
        if (cur != null && cur.control.isNotEmpty() && cur.attempts + 1 >= MAX_ATTEMPTS) {
            Log.w("Outbox", "управляющее событие в ${cur.chatId} исчерпало попытки — удалено")
            remove(id)
            return
        }
        update(id) { entry ->
            val attempts = entry.attempts + 1
            val exhausted = attempts >= MAX_ATTEMPTS
            val backoff = minOf(5_000L shl (attempts - 1).coerceAtMost(7), 5 * 60_000L)
            entry.copy2(
                attempts = attempts,
                nextAttemptAt = container.clock.now() + backoff,
                permanentFailed = entry.permanentFailed || exhausted,
            )
        }
    }

    // применение частичного прогресса при KeyChanged посреди fan-out (A2):
    // уже доставленные участники фиксируются, после accept они не получат копию
    fun applyPartialDelivery(id: String, delivered: Set<String>) {
        if (delivered.isEmpty()) return
        update(id) { it.copy2(deliveredMembers = it.deliveredMembers + delivered) }
    }

    // успех: запись в журнал (ts до отправки) и удаление из очереди.
    // Управляющие события (А3) не журнализируются — локальный эффект уже
    // применён постановщиком. Дедуп-ключ СВОЕЙ записи сеем сразу (C4):
    // копия с другого своего устройства по TYPE_SYNC не задублируется
    private fun complete(session: SessionInfo, entry: Entry, payload: ByteArray, sendTs: Long) {
        if (entry.control.isEmpty()) {
            container.messageRepo.journalOwn(session, entry.chatId, payload, sendTs)
        }
        remove(entry.id)
    }

    // сброс очереди (A2): keepLogin == null — полное стирание («выйти и
    // удалить данные», регистрация нового аккаунта); иначе удаляются только
    // записи ДРУГОГО логина — свои переживают выход и повторный вход
    fun onLogout(keepLogin: String? = null) {
        val victims = _entries.value.filter { keepLogin == null || it.login != keepLogin }
        for (entry in victims) {
            _entries.update { list -> list.filterNot { it.id == entry.id } }
            synchronized(warmupLock) { if (!warmedUp) removedIds += entry.id }
            File(dir, "${entry.id}.box").delete()
            entry.staged?.let { s -> File(attDir, s.fileName).delete() }
        }
        if (keepLogin == null) attDir.deleteRecursively()
    }

    // ─── персистентность: [metaLen 4Б][sealedMeta][sealedPayload] ───
    // Запись атомарна: временный файл + rename (A3). wrapped и
    // groupProgress тоже на диск (A2): после рестарта конверт группы
    // уходит с теми же байтами — ref не расходится у участников

    private fun persist(entry: Entry) {
        try {
            dir.mkdirs()
            val meta = JSONObject()
                .put("id", entry.id)
                .put("chat", entry.chatId)
                .put("login", entry.login)
                .put("preview", entry.preview)
                .put("replyAuthor", entry.replyAuthor ?: JSONObject.NULL)
                .put("replySnippet", entry.replySnippet ?: JSONObject.NULL)
                .put("replyRef", entry.replyRef ?: JSONObject.NULL)
                .put("replyTs", entry.replyTargetTs ?: 0L)
                .put("at", entry.createdAt)
                .put("attempts", entry.attempts)
                .put("nextAt", entry.nextAttemptAt)
                .put("failedFlag", entry.permanentFailed)
                .put("sendAt", entry.sendAt)
                .put("whenOnline", entry.whenOnline)
                .put("delivered", JSONArray(entry.deliveredMembers))
                .put("failed", JSONArray(entry.failedMembers))
                .put("groupProgress", entry.groupProgress)
            if (entry.control.isNotEmpty()) meta.put("ctrl", entry.control)
            if (entry.syncPending) meta.put("syncPending", true)
            if (entry.authHold) meta.put("authHold", true)
            entry.wrapped?.let { w ->
                meta.put("wrapped", Base64.getEncoder().encodeToString(w))
            }
            entry.staged?.let { s ->
                meta.put(
                    "staged",
                    JSONObject()
                        .put("file", s.fileName)
                        .put("key", s.keyB64)
                        .put("desc", s.descriptor),
                )
            }
            val sealedMeta = SecretBox.seal(context, meta.toString().encodeToByteArray())
            val sealedPayload = SecretBox.seal(context, entry.payload ?: ByteArray(0))
            val metaBytes = sealedMeta.encodeToByteArray()
            val out = ByteArray(4 + metaBytes.size + sealedPayload.encodeToByteArray().size)
            out[0] = (metaBytes.size ushr 24).toByte()
            out[1] = (metaBytes.size ushr 16).toByte()
            out[2] = (metaBytes.size ushr 8).toByte()
            out[3] = metaBytes.size.toByte()
            metaBytes.copyInto(out, 4)
            sealedPayload.encodeToByteArray().copyInto(out, 4 + metaBytes.size)
            val target = File(dir, "${entry.id}.box")
            val tmp = File(dir, "${entry.id}.tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "outbox: запись не сохранена" }
            }
        } catch (e: Exception) {
            Log.e("Outbox", "не удалось сохранить запись", e)
        }
    }

    // загрузка с диска: ОДИН раз на процесс (A2) — вызовы из воркера,
    // ShareActivity и уведомлений не воскрешают удалённые записи
    fun warmup() {
        synchronized(warmupLock) {
            if (warmedUp) return
            loadFromDiskLocked()
            warmedUp = true
        }
    }

    private fun loadFromDiskLocked() {
        val files = dir.listFiles { f -> f.name.endsWith(".box") } ?: return
        val loaded = ArrayList<Entry>(files.size)
        for (file in files) {
            try {
                val blob = file.readBytes()
                if (blob.size < 4) continue
                val metaLen = ((blob[0].toInt() and 0xFF) shl 24) or
                    ((blob[1].toInt() and 0xFF) shl 16) or
                    ((blob[2].toInt() and 0xFF) shl 8) or
                    (blob[3].toInt() and 0xFF)
                if (metaLen <= 0 || 4 + metaLen >= blob.size) continue
                val metaBytes = SecretBox.open(context, blob.copyOfRange(4, 4 + metaLen).decodeToString())
                    ?: continue
                val payloadBytes = SecretBox.open(context, blob.copyOfRange(4 + metaLen, blob.size).decodeToString())
                    ?: continue
                val json = JSONObject(metaBytes.decodeToString())
                fun setOf(key: String): Set<String> {
                    val arr = json.optJSONArray(key) ?: return emptySet()
                    return (0 until arr.length()).mapTo(HashSet()) { arr.getString(it) }
                }
                val stagedJson = json.optJSONObject("staged")
                val staged = stagedJson?.let {
                    StagedAttachment(it.getString("file"), it.getString("key"), it.getString("desc"))
                }
                val id = json.getString("id")
                // tombstone (A2): запись удалили, пока шла загрузка
                if (id in removedIds) {
                    file.delete()
                    continue
                }
                val wrappedB64 = json.optString("wrapped", "")
                loaded += Entry(
                    id = id,
                    chatId = json.getString("chat"),
                    login = json.optString("login", ""),
                    // payload восстанавливаем ВСЕГДА (A2): даже при живом staged
                    // он уже закрывает и upload, и повтор — файла может не быть
                    payload = payloadBytes.takeIf { it.isNotEmpty() },
                    preview = json.optString("preview", ""),
                    replyAuthor = if (json.isNull("replyAuthor")) null else json.optString("replyAuthor"),
                    replySnippet = if (json.isNull("replySnippet")) null else json.optString("replySnippet"),
                    replyRef = if (json.isNull("replyRef")) null else json.optString("replyRef"),
                    replyTargetTs = json.optLong("replyTs", 0L).takeIf { it > 0 },
                    createdAt = json.optLong("at", 0L),
                    attempts = json.optInt("attempts", 0),
                    nextAttemptAt = json.optLong("nextAt", 0L),
                    deliveredMembers = setOf("delivered"),
                    failedMembers = setOf("failed"),
                    permanentFailed = json.optBoolean("failedFlag", false),
                    staged = staged,
                    wrapped = wrappedB64.takeIf { it.isNotEmpty() }?.let { b64 ->
                        try {
                            Base64.getDecoder().decode(b64)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    },
                    groupProgress = json.optString("groupProgress", ""),
                    sendAt = json.optLong("sendAt", 0L),
                    whenOnline = json.optBoolean("whenOnline", false),
                    control = json.optString("ctrl", ""),
                    syncPending = json.optBoolean("syncPending", false),
                    authHold = json.optBoolean("authHold", false),
                )
            } catch (e: Exception) {
                Log.w("Outbox", "битая запись outbox удалена", e)
                file.delete()
            }
        }
        _entries.update { currentList ->
            // мердж: записи, добавленные пока шла загрузка, не теряем
            val known = loaded.map { it.id }.toSet()
            (loaded + currentList.filter { it.id !in known }).sortedBy { it.createdAt }
        }
        removedIds.clear()
    }

    // ручная копия (payload — ByteArray, data class непригоден)
    private fun Entry.copy2(
        payload: ByteArray? = this.payload,
        preview: String = this.preview,
        attempts: Int = this.attempts,
        nextAttemptAt: Long = this.nextAttemptAt,
        deliveredMembers: Set<String> = this.deliveredMembers,
        failedMembers: Set<String> = this.failedMembers,
        permanentFailed: Boolean = this.permanentFailed,
        staged: StagedAttachment? = this.staged,
        wrapped: ByteArray? = this.wrapped,
        groupProgress: String = this.groupProgress,
        sendAt: Long = this.sendAt,
        whenOnline: Boolean = this.whenOnline,
        syncPending: Boolean = this.syncPending,
        authHold: Boolean = this.authHold,
    ): Entry = Entry(
        id, chatId, login, payload, preview,
        replyAuthor, replySnippet, replyRef, replyTargetTs,
        createdAt, attempts, nextAttemptAt, deliveredMembers, failedMembers,
        permanentFailed, staged, wrapped, groupProgress, sendAt, whenOnline, control,
        syncPending, authHold,
    )

    companion object {
        private const val MAX_ATTEMPTS = 12
        private const val KEY_CHANGED_RETRY_MS = 60_000L // ждём подтверждения ключей
    }
}
