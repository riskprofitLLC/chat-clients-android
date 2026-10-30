package chat.client.domain

// AI10 (аналог video trim у Telegram): диапазон обрезки видео перед
// отправкой. Чистая логика — клампы и порог полезности, тестируется на JVM.
object VideoTrim {

    // короче секунды резать не даём — это уже не видео
    const val MIN_SPAN_MS = 1_000L

    // рамка «не трогаем»: обрезка короче 0.5 с от полного ролика —
    // перегонять контейнер ради пустяка нет смысла
    const val USEFUL_THRESHOLD_MS = 500L

    data class Span(val startMs: Long, val endMs: Long) {
        val spanMs: Long get() = endMs - startMs
    }

    // нормализация диапазона: кламп в [0, duration], минимум MIN_SPAN_MS,
    // end > start; null — длительность не известна или мусор
    fun sanitize(startMs: Long, endMs: Long, durationMs: Long): Span? {
        if (durationMs <= 0) return null
        var start = startMs.coerceIn(0L, durationMs)
        var end = endMs.coerceIn(0L, durationMs)
        if (end < start) {
            val t = start
            start = end
            end = t
        }
        if (end - start < MIN_SPAN_MS) {
            // растягиваем к минимальной длине, не выходя за границы
            start = (end - MIN_SPAN_MS).coerceAtLeast(0L)
            end = (start + MIN_SPAN_MS).coerceAtMost(durationMs)
            start = (end - MIN_SPAN_MS).coerceAtLeast(0L)
        }
        return Span(start, end)
    }

    // обрезка имеет смысл: забирает заметный кусок хвоста
    fun useful(span: Span, durationMs: Long): Boolean =
        durationMs - span.spanMs > USEFUL_THRESHOLD_MS

    // AL6 (аналог отключения звука в видеоредакторе Telegram):
    // нужна ли пересборка контейнера — либо диапазон режет ролик,
    // либо просят без звука (аудиодорожку выбрасывает пересборка)
    fun needsRebuild(span: Span, durationMs: Long, dropAudio: Boolean): Boolean =
        dropAudio || useful(span, durationMs)
}
