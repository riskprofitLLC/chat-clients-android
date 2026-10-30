package chat.client.domain

import java.time.ZoneId

// GE4 (аналог Stories у Telegram): история — фото с истечением срока.
// В wire едет как обычное TYPE_MEDIA с опциональными полями "st" (exp, мс)
// и "sc" (подпись) — старые клиенты видят просто фото. Чистая логика — JVM-тесты.
object StoryFormat {

    const val TTL_MS = 24L * 60 * 60 * 1000 // сутки, как у Telegram
    const val MAX_FUTURE_MS = 7L * 24 * 60 * 60 * 1000 // защита от мусорного exp

    // срок истечения для новой истории
    fun expiresAt(now: Long, ttlMs: Long = TTL_MS): Long = now + ttlMs

    // валиден ли срок: в будущем, но не дальше MAX_FUTURE_MS
    fun validExp(exp: Long, now: Long): Boolean =
        exp > now && exp <= now + MAX_FUTURE_MS

    fun isExpired(exp: Long, now: Long): Boolean = now >= exp

    // exp → «HH:mm dd.MM» для подписи в просмотре
    fun formatExp(exp: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val ldt = java.time.Instant.ofEpochMilli(exp).atZone(zone).toLocalDateTime()
        return "%02d:%02d %02d.%02d".format(ldt.hour, ldt.minute, ldt.dayOfMonth, ldt.monthValue)
    }
}
