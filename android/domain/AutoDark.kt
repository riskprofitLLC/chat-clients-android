package chat.client.domain

// AH2 (аналог авто-ночного режима Telegram): тёмная тема по расписанию —
// окно «с HH:MM до HH:MM», поддерживает переход через полночь
// («с 22:00 до 07:00»). Чистая логика минут — UI лишь передаёт значения.
object AutoDark {

    const val DEFAULT_FROM = 22 * 60 // 22:00
    const val DEFAULT_TO = 7 * 60    // 07:00

    // минута суток внутри тёмного окна; from==to — окно вырождено,
    // тёмная тема никогда не включается сама
    fun isDark(minuteOfDay: Int, from: Int, to: Int): Boolean {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val f = from.coerceIn(0, 24 * 60 - 1)
        val t = to.coerceIn(0, 24 * 60 - 1)
        if (f == t) return false
        return if (f < t) m in f until t else m >= f || m < t
    }

    // «HH:MM» → минуты от полуночи; null — не парсится
    fun parseMinute(hhmm: String): Int? {
        val parts = hhmm.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    // минуты → «H:MM» для подписей настроек
    fun formatMinute(minute: Int): String {
        val m = minute.coerceIn(0, 24 * 60 - 1)
        return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }
}
