package chat.client.domain

// Тихие часы (Фаза AA3, аналог ночного расписания уведомлений):
// в окне [from, to) входящие уведомления беззвучны — канал silent.
// Чистая логика для JVM-тестов; минуты от полуночи.
object QuietHours {

    // окно тихих часов: from==to — выключено; обычное и через полночь
    fun inWindow(minuteOfDay: Int, from: Int, to: Int): Boolean {
        if (from == to) return false
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        return if (from < to) m in from until to
        else m >= from || m < to
    }

    // минуты от полуночи ПО МЕСТНОМУ времени (Calendar — на всех API;
    // java.time доступен только с 26). Раньше считалось по UTC —
    // окно тихих часов съезжало на часовой пояс устройства
    fun minuteOfDay(ms: Long): Int {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    // действует ли сейчас: выключено/окно — обычное уведомление
    fun active(enabled: Boolean, fromMin: Int, toMin: Int, nowMs: Long): Boolean =
        enabled && inWindow(minuteOfDay(nowMs), fromMin, toMin)

    // «23:00»-подпись для настроек; 1380 → «23:00»
    fun label(minute: Int): String = "%d:%02d".format(minute / 60, minute % 60)
}
