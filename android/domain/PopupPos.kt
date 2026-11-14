package chat.client.domain

// Фаза AE2 (аналог popup video у Telegram): геометрия плавающего окна
// видео — кламп в границы экрана. Чистая логика, JVM-тесты.
object PopupPos {

    // окно обязано оставаться целиком внутри контейнера
    fun clamp(x: Float, y: Float, containerW: Float, containerH: Float, w: Float, h: Float): Pair<Float, Float> {
        if (containerW <= 0f || containerH <= 0f) return x to y
        val cx = x.coerceIn(0f, (containerW - w).coerceAtLeast(0f))
        val cy = y.coerceIn(0f, (containerH - h).coerceAtLeast(0f))
        return cx to cy
    }
}
