package chat.client.data

import android.util.Log
import chat.client.domain.GroupRules.GroupRuleException
import chat.client.domain.MessageCodec
import chat.client.domain.Validators

// Сводка чата для списка: последнее сообщение (реакции исключены),
// непрочитанные, пин/мьют, архив, черновик.
class ChatSummary(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val isSelf: Boolean, // «Избранное»: чат с самим собой
    val membersCount: Int,
    val lastTs: Long?,
    val lastPreview: String?,
    // тип последней записи — иконка в строке чата вместо эмодзи (B4)
    val lastPreviewKind: chat.client.domain.MessageCodec.PreviewKind? = null,
    val lastMine: Boolean,
    // ✓✓ превью последнего своего сообщения в строке чата (D3)
    val lastDelivered: Boolean = false,
    val lastRead: Boolean = false,
    val unread: Int,
    val pinned: Boolean,
    val mutedUntil: Long, // Фаза M3: срок мьюта; UI сверяет с текущим временем
    val archived: Boolean = false,
    val draft: String = "",
    val markedUnread: Boolean = false, // ручная отметка «непрочитано» (Фаза O3)
    // Фаза AE4 (аналог @-бейджа у Telegram): непрочитанные упоминания
    val mentions: Int = 0,
    // аватар чата (Фаза U1): фото профиля пира/группы/себя; null — инициалы
    val avatar: chat.client.domain.MediaInfo? = null,
    // Фаза AE7 (аналог эмодзи-аватара группы): аватар-эмодзи без фото
    val emoji: String? = null,
    // AH4 (аналог Hidden chats): чат за PIN — не в списке и поиске,
    // уведомления без имени и текста
    val hidden: Boolean = false,
)

// Результат глобального поиска: конкретное сообщение с адресом прыжка
class MessageHit(
    val chatId: String,
    val chatTitle: String,
    val isGroup: Boolean,
    val isSelf: Boolean,
    val ts: Long,
    val mine: Boolean,
    val snippet: String,
    // аватар чата для строки результата (Фаза U1)
    val avatar: chat.client.domain.MediaInfo? = null,
    // Фаза AE7: эмодзи-аватар группы в строке результата
    val emoji: String? = null,
    // табы типов результата: media/file/voice и mime вложения (null — текст)
    val mediaType: String? = null,
    val mime: String? = null,
)

// Список чатов: построение сводок, инкрементальное обновление,
// поиск (по названию и по тексту сообщений), удаление чата.
// Записи сортируются по ts (A13): после импорта бэкапа порядок в сторе
// не гарантирован — превью и непрочитанные считаем по максимуму.
class ChatRepository(private val container: AppContainer) {

    // кэш сводок (C1): подпись = поколение записи + lastRead + флаги.
    // Повторные summaries() после дрейна не расшифровывают всю историю
    // чатов, где ничего не изменилось
    private class CacheEntry(val signature: Long, val summary: ChatSummary)

    private val summaryCache = HashMap<String, CacheEntry>()

    // сводки и поиск — из разных потоков (A17): доступ к кэшу сериализуем
    @Synchronized
    private fun signatureOf(session: SessionInfo, chatId: String, lastRead: Long): Long {
        var s = session.store.generation(chatId)
        s = s * 31L + lastRead
        s = s * 31L + if (container.chatPrefs.pinned(chatId)) 1L else 0L
        s = s * 31L + container.chatPrefs.mutedUntil(chatId)
        s = s * 31L + if (container.chatPrefs.archived(chatId)) 1L else 0L
        s = s * 31L + if (container.chatPrefs.hidden(chatId)) 1L else 0L
        s = s * 31L + container.chatPrefs.draft(chatId).hashCode()
        // локальное имя входит в подпись кэша: переименование обновляет строку
        s = s * 31L + (container.chatPrefs.name(chatId)?.hashCode()?.toLong() ?: 0L)
        // ручная отметка «непрочитано» входит в подпись кэша (Фаза O3)
        s = s * 31L + if (container.unread.isMarkedUnread(chatId)) 1L else 0L
        // стабильная подпись группы (B9): у GroupChat (data class) — имена,
        // состав и ревизия; прежний hashCode нового объекта каждый раз
        // убивал кэш для каждой группы
        s = s * 31L + (container.groups.byId(chatId)?.hashCode()?.toLong() ?: 0L)
        // аватар чата (Фаза U1): смена фото меняет строку списка
        s = s * 31L + (avatarOf(session.login, chatId)?.id?.hashCode()?.toLong() ?: 0L)
        // Фаза AE4: счётчик непрочитанных упоминаний — @-бейдж строки
        s = s * 31L + container.unread.mentionCount(chatId)
        // Фаза AE7: эмодзи-аватар группы меняет строку без фото
        s = s * 31L + (emojiOf(chatId)?.hashCode()?.toLong() ?: 0L)
        return s
    }

    // аватар чата: свой — по своему логину, группы — по chatId (Фаза U1)
    private fun avatarOf(myLogin: String, chatId: String): chat.client.domain.MediaInfo? =
        when {
            chatId == myLogin -> container.avatars.userOf(myLogin)
            chatId.startsWith("grp_") -> container.avatars.groupOf(chatId)
            else -> container.avatars.userOf(chatId)
        }

    // Фаза AE7: эмодзи-аватар — только у групп
    private fun emojiOf(chatId: String): String? =
        if (chatId.startsWith("grp_")) container.avatars.groupEmoji(chatId) else null

    // сводка с кэшем: неизменные чаты не пересчитываются (C1)
    @Synchronized
    private fun summaryOf(session: SessionInfo, chatId: String): ChatSummary {
        val lastRead = container.unread.lastRead(chatId)
        val sig = signatureOf(session, chatId, lastRead)
        summaryCache[chatId]?.let { cached ->
            if (cached.signature == sig) return cached.summary
        }
        val fresh = buildSummaries(session, listOf(chatId)).first()
        summaryCache[chatId] = CacheEntry(sig, fresh)
        return fresh
    }

    // полная пересборка списка; «Избранное» присутствует всегда.
    // Кэш (C1) делает повторные сборки дешёвыми: расшифровка только тех
    // чатов, где были записи с прошлого раза
    fun summaries(session: SessionInfo): List<ChatSummary> {
        val groups = container.groups.all().associateBy { it.id }
        val ids = (session.store.chatIds() + groups.keys + session.login).distinct()
        return ids.filter { it.isNotEmpty() }.map { summaryOf(session, it) }.sortChats()
    }

    // инкрементально: только изменившиеся чаты (после дрейна)
    fun summariesFor(session: SessionInfo, chatIds: Collection<String>): List<ChatSummary> {
        if (chatIds.isEmpty()) return emptyList()
        val out = ArrayList<ChatSummary>(chatIds.size)
        for (chatId in chatIds) {
            if (chatId.isEmpty()) continue
            out += summaryOf(session, chatId)
        }
        return out.sortChats()
    }

    private fun List<ChatSummary>.sortChats(): List<ChatSummary> =
        ChatListLayout.sort(this, container.chatPrefs.pinOrder())

    // заголовок чата: группы — имя, свой логин — «Избранное»,
    // остальным — локальное имя поверх логина
    private fun titleOf(session: SessionInfo, chatId: String, group: chat.client.data.GroupChat?): String =
        if (chatId == session.login) {
            container.app.getString(chat.client.R.string.saved_messages)
        } else {
            container.chatPrefs.name(chatId) ?: group?.name ?: chatId
        }

    private fun buildSummaries(session: SessionInfo, chatIds: Collection<String>): List<ChatSummary> {
        val myLogin = session.login
        val legacySince = container.appPrefs.legacyDecodeSince()
        val out = ArrayList<ChatSummary>(chatIds.size)
        for (chatId in chatIds) {
            val group = container.groups.byId(chatId)
            // полная история: непрочитанные считаются по всем записям, а не
            // по последним 500 (A13)
            val history = session.store.history(chatId, Int.MAX_VALUE)
                .sortedBy { it.timestamp }
            val lastRead = container.unread.lastRead(chatId)
            var unread = 0
            // последняя «настоящая» запись: реакции и голоса не считаются
            var last: chat.crypto.MessageRecord? = null
            for (record in history) {
                val parsed = Session.parseRecord(record, legacySince)
                if (parsed is MessageCodec.Parsed.ReactionOnly) continue
                // голос в опросе — не сообщение (Фаза W1)
                if (parsed is MessageCodec.Parsed.VoteOnly) continue
                last = record
                if (record.timestamp > lastRead && record.from != myLogin) unread++
            }
            // ✓✓ своего последнего в строке (D3): те же критерии, что в диалоге
            val isGroup = group != null
            val deliveredAt = container.chatPrefs.deliveredAt(chatId)
            val readAt = container.chatPrefs.readAt(chatId)
            val groupDelivered = container.chatPrefs.groupDeliveredAt(chatId)
            val lastDelivered = last != null && last.from == myLogin && (
                if (!isGroup) {
                    last.timestamp <= deliveredAt
                } else {
                    group != null && groupDelivered.any { (member, at) ->
                        member != myLogin && member in group.members && last.timestamp <= at
                    }
                }
                )
            val lastReadFlag = last != null && last.from == myLogin && !isGroup &&
                last.timestamp <= readAt
            out += ChatSummary(
                id = chatId,
                title = titleOf(session, chatId, group),
                isGroup = group != null,
                isSelf = chatId == myLogin,
                membersCount = group?.members?.size ?: 0,
                lastTs = last?.timestamp,
                // A11: легаси-разбор только для записей до миграции
                lastPreview = last?.let {
                    MessageCodec.preview(it.content, allowLegacy = it.timestamp < legacySince)?.text
                },
                lastPreviewKind = last?.let {
                    MessageCodec.preview(it.content, allowLegacy = it.timestamp < legacySince)?.kind
                },
                lastMine = last?.from == myLogin,
                lastDelivered = lastDelivered,
                lastRead = lastReadFlag,
                unread = unread,
                pinned = container.chatPrefs.pinned(chatId),
                mutedUntil = container.chatPrefs.mutedUntil(chatId),
                archived = container.chatPrefs.archived(chatId),
                draft = container.chatPrefs.draft(chatId),
                markedUnread = container.unread.isMarkedUnread(chatId),
                avatar = avatarOf(myLogin, chatId),
                mentions = container.unread.mentionCount(chatId),
                emoji = if (isGroup) emojiOf(chatId) else null,
                hidden = container.chatPrefs.hidden(chatId),
            )
        }
        return out
    }

    // поиск по названию чата и тексту сообщений
    fun search(session: SessionInfo, query: String): List<ChatSummary> {
        if (query.isBlank()) return summaries(session)
        val q = query.trim()
        val groups = container.groups.all().associateBy { it.id }
        val ids = (session.store.chatIds() + groups.keys + session.login).distinct()
        val out = ArrayList<ChatSummary>()
        val legacySince = container.appPrefs.legacyDecodeSince()
        for (chatId in ids) {
            // AH4: скрытые чаты не ищутся — приватность важнее находчивости
            if (container.chatPrefs.hidden(chatId)) continue
            val group = groups[chatId]
            val title = titleOf(session, chatId, group)
            val titleMatches = title.contains(q, ignoreCase = true)
            var textMatches = false
            if (!titleMatches) {
                outer@ for (record in session.store.history(chatId, 500)) {
                    when (val p = Session.parseRecord(record, legacySince)) {
                        is MessageCodec.Parsed.ReactionOnly -> {}
                        // голос в опросе не ищет чат (Фаза W1)
                        is MessageCodec.Parsed.VoteOnly -> {}
                        is MessageCodec.Parsed.Content -> when (val k = p.kind) {
                            is MessageCodec.Kind.Text -> if (k.text.contains(q, true)) {
                                textMatches = true; break@outer
                            }
                            is MessageCodec.Kind.Reply -> if (k.text.contains(q, true)) {
                                textMatches = true; break@outer
                            }
                            else -> {}
                        }
                    }
                }
            }
            if (titleMatches || textMatches) {
                out += summaryOf(session, chatId)
            }
        }
        return out.sortChats()
    }

    // ─── глобальный поиск по тексту (C2): индекс plaintext в памяти ───
    // Инвалидация — по поколению записи StoreGate: расшифровка истории
    // происходит один раз на изменение, а не на каждый запрос

    private class IndexedLine(
        val ts: Long,
        val from: String,
        val mine: Boolean,
        val text: String,
        // табы типов результата: тип вложения и mime (null — не медиа)
        val mediaType: String? = null,
        val mime: String? = null,
    )
    private class ChatTextIndex(val generation: Long, val lines: List<IndexedLine>)
    private val searchIndex = HashMap<String, ChatTextIndex>()

    @Synchronized
    private fun textIndex(session: SessionInfo, chatId: String): List<IndexedLine> {
        val generation = session.store.generation(chatId)
        searchIndex[chatId]?.let { cached ->
            if (cached.generation == generation) return cached.lines
        }
        val legacySince = container.appPrefs.legacyDecodeSince()
        val lines = ArrayList<IndexedLine>()
        for (record in session.store.history(chatId, Int.MAX_VALUE)) {
            val parsed = Session.parseRecord(record, legacySince)
            val kind = (parsed as? MessageCodec.Parsed.Content)?.kind ?: continue
            val text = when (kind) {
                is MessageCodec.Kind.Text -> kind.text
                is MessageCodec.Kind.Reply -> kind.text
                // опрос ищется по вопросу, контакт — по имени (Фазы W1/W3)
                is MessageCodec.Kind.Poll -> kind.question
                is MessageCodec.Kind.Contact -> kind.name ?: "@${kind.login}"
                // медиа и файлы ищутся по имени вложения — иначе табы
                // «Медиа»/«Файлы» в глобальном поиске всегда пусты
                is MessageCodec.Kind.Media -> {
                    // аватар — служебный конверт, не сообщение чата
                    if (kind.info.avatarOwner != null || kind.info.avatarRemove) continue
                    kind.info.name?.takeIf { it.isNotBlank() } ?: continue
                }
                else -> continue
            }
            val media = kind as? MessageCodec.Kind.Media
            lines += IndexedLine(
                record.timestamp,
                record.from,
                record.from == session.login,
                text,
                media?.info?.type,
                media?.info?.mime,
            )
        }
        searchIndex[chatId] = ChatTextIndex(generation, lines)
        return lines
    }

    // глобальный поиск сообщений: конкретные записи по всем чатам,
    // прыжок в диалог по ts (по образцу Telegram «Сообщения» в поиске)
    fun searchMessages(session: SessionInfo, query: String, limit: Int = 50): List<MessageHit> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val groups = container.groups.all().associateBy { it.id }
        val ids = (session.store.chatIds() + groups.keys + session.login).distinct()
        val hits = ArrayList<MessageHit>()
        for (chatId in ids) {
            // AH4: сообщения скрытых чатов не ищутся
            if (container.chatPrefs.hidden(chatId)) continue
            val group = groups[chatId]
            for (line in textIndex(session, chatId)) {
                if (!line.text.contains(q, ignoreCase = true)) continue
                hits += MessageHit(
                    chatId = chatId,
                    chatTitle = titleOf(session, chatId, group),
                    isGroup = group != null,
                    isSelf = chatId == session.login,
                    ts = line.ts,
                    mine = line.mine,
                    snippet = chat.client.domain.GlobalSearch.snippet(line.text, q),
                    avatar = avatarOf(session.login, chatId),
                    emoji = emojiOf(chatId),
                    mediaType = line.mediaType,
                    mime = line.mime,
                )
            }
        }
        return chat.client.domain.GlobalSearch.cap(hits, { it.chatId }, { it.ts }, total = limit)
    }

    // новый чат: логин валиден и пользователь существует на сервере.
    // 3.1: сетевые ошибки НЕ проглатываются — офлайн больше не врёт
    // «не зарегистрирован»; 404 остаётся 404
    fun newChat(session: SessionInfo, login: String): Result<String> {
        val normalized = login.trim().lowercase()
        if (!Validators.validLogin(normalized)) {
            return Result.failure(
                GroupRuleException(GroupRuleException.INVALID_LOGIN),
            )
        }
        // S6: grp_ — пространство имён групповых чатов, личного чата с ним нет
        if (normalized.startsWith("grp_")) {
            return Result.failure(
                GroupRuleException(GroupRuleException.INVALID_LOGIN),
            )
        }
        return try {
            if (container.messageRepo.peerExistsStrict(session, normalized)) {
                Result.success(normalized)
            } else {
                Result.failure(chat.client.net.RelayException(404, "$normalized не зарегистрирован"))
            }
        } catch (e: Exception) {
            // честная причина: сеть/сервер — вызывающий решает, что показать
            Result.failure(e)
        }
    }

    // удаление локальной истории чата + осиротевшие настройки + медиа (A19)
    fun deleteChatHistory(session: SessionInfo, chatId: String) {
        val legacySince = container.appPrefs.legacyDecodeSince()
        val history = session.store.history(chatId, Int.MAX_VALUE)
        val mediaIds = HashSet<String>()
        for (record in history) {
            collectMediaId(record, legacySince)?.let { mediaIds += it }
        }
        // пакетное удаление (C2): один lock и одна инвалидация
        session.store.deleteRecords(chatId, history)
        for (id in mediaIds) container.media.deleteIfUnused(session, id)
        searchIndex.remove(chatId)
        cleanupOrphans(session, chatId)
    }

    // группа: покинуть и удалить (локально; у участников копия остаётся).
    // A4: id попадает в локальный block-list — новые конверты и повторные
    // приглашения от участников не воскрешают чат
    fun leaveGroup(session: SessionInfo, chatId: String) {
        container.groups.delete(chatId)
        container.groups.markLeft(chatId)
        deleteChatHistory(session, chatId)
    }

    // переименование локального определения группы

    // отметить всё импортированное/перенесённое прочитанным (A17):
    // иначе вся старая история вздувает счётчик непрочитанных
    fun markAllRead(session: SessionInfo) {
        for (chatId in session.store.chatIds()) {
            container.unread.markRead(chatId)
        }
    }

    // ─── AI9 (аналог per-chat storage у Telegram): строки использования
    // по чатам и чистка локальных медиа одного чата ───

    // строки по всем чатам: медиа-id и точные байты; группы и
    // «Избранное» — те же chat-*.log файлы. Единый источник для экрана
    // «Хранилище» и чистки из инфо-экрана
    fun usageRows(session: SessionInfo): List<chat.client.domain.ChatUsage> {
        val ids = (session.store.chatIds() + container.groups.all().map { it.id }).distinct()
        val chats = ArrayList<chat.client.domain.ChatUsage>(ids.size)
        for (chatId in ids) {
            val records = try {
                session.store.history(chatId, Int.MAX_VALUE)
            } catch (_: Exception) {
                emptyList<chat.crypto.MessageRecord>()
            }
            val mediaIds = HashSet<String>()
            for (record in records) {
                when (val parsed = MessageCodec.parse(record.content, allowLegacy = true)) {
                    is MessageCodec.Parsed.Content -> {
                        if (parsed.kind is MessageCodec.Kind.Media) mediaIds += parsed.kind.info.id
                    }
                    else -> Unit
                }
            }
            val journalBytes = try {
                val f = java.io.File(java.io.File(container.app.filesDir, "store"), "chat-$chatId.log")
                if (f.exists()) f.length() else 0L
            } catch (_: Exception) {
                0L
            }
            val chatMediaBytes = chat.client.media.Media.sizeOf(container.app, mediaIds)
            if (journalBytes > 0 || mediaIds.isNotEmpty()) {
                chats += chat.client.domain.ChatUsage(chatId, mediaIds, chatMediaBytes, journalBytes)
            }
        }
        return chats
    }

    // локальные медиа-байты одного чата (для строки в инфо-экране)
    fun chatMediaBytes(session: SessionInfo, chatId: String): Long {
        val mediaIds = HashSet<String>()
        val records = try {
            session.store.history(chatId, Int.MAX_VALUE)
        } catch (_: Exception) {
            emptyList<chat.crypto.MessageRecord>()
        }
        for (record in records) {
            val kind = (MessageCodec.parse(record.content, allowLegacy = true) as? MessageCodec.Parsed.Content)?.kind
            if (kind is MessageCodec.Kind.Media) mediaIds += kind.info.id
        }
        return chat.client.media.Media.sizeOf(container.app, mediaIds)
    }

    // чистка медиа одного чата: удаляем только уникальные для него id
    // (общие с другими чатами не трогаем — иначе сломаем чужие записи);
    // записи истории не трогаем — дескрипторы остаются, медиа докачается.
    // Файлы, которых уже нет, не считаются работой: повтор чистки — успех.
    // true — чистка прошла (в т.ч. «удалять было нечего»)
    fun clearChatMedia(context: android.content.Context, session: SessionInfo, chatId: String): Boolean {
        val rows = try {
            usageRows(session)
        } catch (_: Exception) {
            return false
        }
        val deletable = chat.client.domain.StorageUsageLogic.deletableFor(chatId, rows)
            .filter { chat.client.media.Media.exists(context, it) }
        return deletable.isEmpty() || chat.client.media.Media.deleteAll(context, deletable) > 0
    }

    private fun collectMediaId(record: chat.crypto.MessageRecord, legacySince: Long): String? {
        val parsed = Session.parseRecord(record, legacySince)
        val kind = (parsed as? MessageCodec.Parsed.Content)?.kind ?: return null
        return (kind as? MessageCodec.Kind.Media)?.info?.id
    }

    private fun cleanupOrphans(session: SessionInfo, chatId: String) {
        container.chatPrefs.cleanup(chatId)
        container.unread.cleanup(chatId)
        container.destruct.cleanupChat(chatId)
        container.avatars.cleanup(chatId)
        // Фаза AB5: вместе с чатом удаляем его личные обои
        container.wallpapers.remove(chatId)
        container.outbox.dropAllFor(chatId)
        // TOFU-ключи НЕ трогаем (A4): удаление истории — не «забыть контакт»,
        // иначе смена ключа после очистки прошла бы молча как «первая встреча»
        Log.d("ChatRepo", "чат $chatId удалён, сиротеские настройки вычищены")
    }
}
