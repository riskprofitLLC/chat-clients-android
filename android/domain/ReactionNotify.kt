package chat.client.domain

// AL9 (аналог уведомлений «Reacted to your message» у Telegram):
// реакция на СВОЁ сообщение — событие, достойное шторки. Домен
// решает, чью цель задела реакция: поиск по журналу чата.
object ReactionNotify {

    // заметка о реакции для уведомления: чат, автор (логин), эмодзи,
    // цель — стабильная ссылка и/или ts (старые записи)
    class Notice(
        val chatId: String,
        val author: String,
        val emoji: String,
        val targetRef: String?,
        val targetTs: Long?,
    )

    // запись журнала для проверки цели: автор, ts, байты конверта
    class Record(val from: String, val ts: Long, val content: ByteArray?)

    // реакция значима, только если задела МОЁ сообщение (чужие
    // реакции на чужие письма не дёргают шторку); не найденная цель
    // (удалена/за окном сканирования) не уведомляется — тишина
    // лучше ложного тычка
    fun targetsMine(
        records: List<Record>,
        targetRef: String?,
        targetTs: Long?,
        myLogin: String,
    ): Boolean {
        val candidates = records.filter { it.from == myLogin }
        if (candidates.isEmpty()) return false
        if (targetRef != null) {
            for (r in candidates) {
                val content = r.content ?: continue
                if (MessageRef.matches(targetRef, content)) return true
            }
        }
        if (targetTs != null && candidates.any { it.ts == targetTs }) return true
        return false
    }

    // свои реакции (с других своих устройств) не уведомляем
    fun authorizable(notice: Notice, myLogin: String): Boolean =
        notice.author != myLogin
}
