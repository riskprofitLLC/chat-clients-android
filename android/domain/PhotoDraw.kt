package chat.client.domain

// Фаза AD10 (аналог рисования на фото у Telegram): штрихи поверх
// снимка перед отправкой. Координаты — доли изображения 0..1, толщина —
// доля длинной стороны; единая модель для композ-канваса и наложения
// на битмап (android.graphics.Canvas) — визуальный масштаб совпадает.
object PhotoDraw {

    // один штрих: цвет (ARGB), толщина (доля длинной стороны), точки
    class Stroke(val color: Long, val width: Float, val points: List<Pair<Float, Float>>) {
        val size: Int get() = points.size
    }

    // палитра и толщины — как у Telegram: несколько ярких + чёрный/белый
    val COLORS: List<Long> = listOf(
        0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFFA726,
        0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA,
    )

    // толщина кисти: доли длинной стороны (на 2048px: ~4/8/16 px)
    val WIDTHS: List<Float> = listOf(0.002f, 0.004f, 0.008f)

    fun validWidth(w: Float): Boolean = w in WIDTHS

    // нормализация координаты
    fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    // прореживание точек: ближе MIN_STEP — не добавляем (пути
    // не разбухают от микрожестов, N точек вместо сотен)
    const val MIN_STEP = 0.004f

    fun shouldAppend(points: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        val last = points.lastOrNull() ?: return true
        val dx = x - last.first
        val dy = y - last.second
        return dx * dx + dy * dy >= MIN_STEP * MIN_STEP
    }

    // отмена последнего штриха
    fun undo(strokes: List<Stroke>): List<Stroke> = strokes.dropLast(1)

    // потолок штрихов: старейшие вытесняются (безопасная память)
    const val MAX_STROKES = 200

    fun append(strokes: List<Stroke>, stroke: Stroke): List<Stroke> =
        (strokes + stroke).takeLast(MAX_STROKES)

    // наложение штрихов на битмап (android.graphics): та же геометрия
    // сглаживания, что у композ-канваса — визуальный масштаб совпадает;
    // ARGB_8888-копия исходника, штрихи поверх
    fun renderOnto(source: android.graphics.Bitmap, strokes: List<Stroke>): android.graphics.Bitmap {
        val out = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val long = maxOf(out.width, out.height).toFloat()
        val canvas = android.graphics.Canvas(out)
        for (st in strokes) {
            if (st.points.isEmpty()) continue
            val paint = android.graphics.Paint().apply {
                color = st.color.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = st.width * long
                isAntiAlias = true
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            val path = android.graphics.Path()
            val p0 = st.points.first()
            path.moveTo(p0.first * out.width, p0.second * out.height)
            if (st.points.size == 1) {
                path.lineTo(p0.first * out.width + 0.5f, p0.second * out.height)
            } else {
                for (i in 1 until st.points.size) {
                    val prev = st.points[i - 1]
                    val cur = st.points[i]
                    val midX = (prev.first + cur.first) / 2f * out.width
                    val midY = (prev.second + cur.second) / 2f * out.height
                    path.quadTo(prev.first * out.width, prev.second * out.height, midX, midY)
                }
                val last = st.points.last()
                path.lineTo(last.first * out.width, last.second * out.height)
            }
            canvas.drawPath(path, paint)
        }
        return out
    }
}
