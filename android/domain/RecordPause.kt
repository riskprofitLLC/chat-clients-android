package chat.client.domain

// AL-раунд (аналог pause в записи видео-кружков Telegram): чистая
// арифметика часов записи с паузами — одна для голосовых (U3) и
// кружков; платформенные плееры только подставляют свои метки.
object RecordPause {

    // чистое время записи без пауз, мс; старт<=0 — запись не началась,
    // честный ноль (асинхронный старт MediaRecorder не должен рисовать
    // «уже секунду пишем»)
    fun elapsedMs(startMs: Long, nowMs: Long, pausedMs: Long, pauseStartMs: Long): Long {
        if (startMs <= 0L) return 0L
        val now = if (pauseStartMs > 0L) pauseStartMs else nowMs
        return (now - startMs - pausedMs).coerceAtLeast(0L)
    }

    // суммарное время паузы к моменту завершения: активная пауза
    // закрывается, повторный вызов идемпотентен
    fun totalPausedMs(nowMs: Long, pausedMs: Long, pauseStartMs: Long): Long =
        if (pauseStartMs > 0L) pausedMs + (nowMs - pauseStartMs).coerceAtLeast(0L) else pausedMs
}
