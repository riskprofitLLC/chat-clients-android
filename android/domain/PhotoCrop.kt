package chat.client.domain

// Кроп-прямоугольник в долях изображения (Фаза AB2): жест даёт любые
// координаты, здесь они приводятся к рабочему виду — зажим в 0..1,
// порядок сторон и минимальная сторона 15%.
object PhotoCrop {

    // минимальная сторона кадра (доли изображения)
    const val MIN_SIDE = 0.15f

    // нормализация [l, t, r, b]: зажим, разворот сторон, мин-сторона
    fun rect(l: Float, t: Float, r: Float, b: Float): FloatArray {
        var left = l.coerceIn(0f, 1f)
        var top = t.coerceIn(0f, 1f)
        var right = r.coerceIn(0f, 1f)
        var bottom = b.coerceIn(0f, 1f)
        // разворот: правая/нижняя сторона не меньше левой/верхней
        if (right < left) {
            val swap = left
            left = right
            right = swap
        }
        if (bottom < top) {
            val swap = top
            top = bottom
            bottom = swap
        }
        // минимальная сторона: расширение от центра, у края — сдвиг вовнутрь
        if (right - left < MIN_SIDE) {
            val cx = (left + right) / 2f
            left = (cx - MIN_SIDE / 2f).coerceIn(0f, 1f - MIN_SIDE)
            right = left + MIN_SIDE
        }
        if (bottom - top < MIN_SIDE) {
            val cy = (top + bottom) / 2f
            top = (cy - MIN_SIDE / 2f).coerceIn(0f, 1f - MIN_SIDE)
            bottom = top + MIN_SIDE
        }
        return floatArrayOf(left, top, right, bottom)
    }
}
