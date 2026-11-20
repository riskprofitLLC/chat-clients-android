package chat.client.domain

import chat.crypto.MessageStore

// «Удалить у всех» (Фаза P1): применение события TYPE_DELETE к локальному
// журналу. Чистая логика без Android — тестируется на JVM с
// JournalMessageStore. Цель ищется по ref (байты контента идентичны у
// обеих сторон), фолбэк — по ts для старых записей. Удалить можно ТОЛЬКО
// сообщение автора кадра (A4): собеседник не может снести чужое.
// Реакции на цель снимаются вместе с ней — как при локальном удалении.
object MessageDelete {

    fun apply(
        store: MessageStore,
        chatId: String,
        expectedAuthor: String?,
        targetRef: String?,
        targetTs: Long?,
        window: Int = 2000, // окно поиска цели (C1)
        // AI3: админы группы могут удалять и чужие сообщения
        admins: Set<String> = emptySet(),
    ): Boolean {
        val history = store.history(chatId, window).sortedBy { it.timestamp }
        val target = history.firstOrNull {
            targetRef != null && MessageRef.matches(targetRef, it.content)
        } ?: history.firstOrNull { targetTs != null && it.timestamp == targetTs }
            ?: return false
        // цель обязана принадлежать автору события (A4); админ группы —
        // исключение модерации (AI3)
        if (expectedAuthor != null && target.from != expectedAuthor && expectedAuthor !in admins) return false
        // реакции и голоса в опросе (W1) умирают вместе с целью
        for (record in history) {
            val parsed = MessageCodec.parse(record.content, allowLegacy = false)
            when (parsed) {
                is MessageCodec.Parsed.ReactionOnly -> {
                    val r = parsed.reaction
                    if (r.targetRef == targetRef || (r.targetRef == null && r.targetTs == target.timestamp)) {
                        store.deleteRecord(chatId, record.timestamp, record.content)
                    }
                }
                is MessageCodec.Parsed.VoteOnly -> {
                    if (parsed.vote.targetRef?.let { MessageRef.sameRef(it, MessageRef.of(target.content)) } == true) {
                        store.deleteRecord(chatId, record.timestamp, record.content)
                    }
                }
                else -> {}
            }
        }
        store.deleteRecord(chatId, target.timestamp, target.content)
        return true
    }
}
