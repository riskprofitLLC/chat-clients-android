package chat.client.domain

// Живая геопозиция (Фаза AA7, аналог shareLiveLocation у Telegram):
// трансляция точки раз в минуту, пока не истечёт срок. Чистая логика
// сроков — JVM-тесты; сама отправка — тикер ConnectionManager.
object LiveGeo {

    val DURATIONS_MIN = intArrayOf(15, 60, 480) // 15 минут / 1 час / 8 часов

    const val SEND_INTERVAL_MS = 60_000L

    fun untilFor(minutes: Int, now: Long): Long =
        now + minutes * 60_000L

    // осталось минут трансляции (для плашки); 0 — истекла
    fun remainingMin(until: Long, now: Long): Int =
        (((until - now) / 60_000L).coerceAtLeast(0L)).toInt()

    // пора ли слать точку: чаще минуты не шлём
    fun shouldSend(lastSentMs: Long, now: Long): Boolean =
        lastSentMs <= 0L || now - lastSentMs >= SEND_INTERVAL_MS
}
