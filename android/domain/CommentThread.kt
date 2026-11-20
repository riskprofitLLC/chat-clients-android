package chat.client.domain

// GE5 (аналог Comments у Telegram): обсуждение поста — все ответы,
// указывающие на корневое сообщение (targetRef совпадает с ref корня).
// Чистая логика без Android — тестируется на JVM.
object CommentThread {

    class Entry(
        val id: String,
        val ts: Long,
        val targetRef: String?, // null — не ответ
    )

    // тред корня: только ответы на него, по времени; сам корень не входит
    fun of(rootRef: String, entries: List<Entry>): List<Entry> =
        entries
            .filter { it.targetRef != null && MessageRef.sameRef(it.targetRef!!, rootRef) }
            .sortedWith(compareBy<Entry> { it.ts }.thenBy { it.id })
}
