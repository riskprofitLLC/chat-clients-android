package chat.client.domain

// AI2 (аналог статуса «в сети» у Telegram): чистая свёртка ответа
// сервера (онлайн + время последнего разрыва WS) в человекочитаемую
// метку шапки 1:1-диалога. Строки не трогаем — UI локализует.
object Presence {

    // опрос статуса при открытом чате, 30 с
    const val POLL_MS = 30_000L

    // окно «был(а) недавно»: свежесть последнего визита
    const val RECENT_WINDOW_MS = 5 * 60 * 1000L

    sealed class Status {
        object Online : Status()
        object Unknown : Status()
        object Recently : Status()
        data class MinutesAgo(val minutes: Int) : Status()
        data class TodayAt(val hhmm: String) : Status()
        data class AtDate(val date: String) : Status()
    }

    // online перекрывает время; ноль/отсутствие времени — «неизвестно»;
    // часы убежали вперёд — тоже не пугаем «был(а) в будущем».
    // Один календарный день — «сегодня в H:MM» (как у Telegram),
    // старше — дата последнего визита
    fun status(
        online: Boolean,
        lastSeenMs: Long?,
        nowMs: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Status {
        if (online) return Status.Online
        val seen = lastSeenMs ?: return Status.Unknown
        if (seen <= 0) return Status.Unknown
        val age = nowMs - seen
        if (age < 0) return Status.Unknown
        if (age <= RECENT_WINDOW_MS) return Status.Recently
        val minutes = (age / 60_000L).toInt()
        if (minutes < 60) return Status.MinutesAgo(minutes)
        val seenAt = java.time.Instant.ofEpochMilli(seen).atZone(zone)
        val nowAt = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        val hhmm = java.time.format.DateTimeFormatter.ofPattern("H:mm").format(seenAt)
        if (seenAt.toLocalDate() == nowAt.toLocalDate()) return Status.TodayAt(hhmm)
        val date = java.time.format.DateTimeFormatter.ofPattern("d MMM").format(seenAt)
        return Status.AtDate(date)
    }
}
