package chat.client.domain

// AL5 (аналог инструмента затемнения/пикселизации в фоторедакторе
// Telegram, см. PhotoFilterBlurControl): кисть «Пиксель» по фото —
// мазок замешивает под собой крупнопиксельную копию снимка, пряча
// лица и детали без потери остального кадра. Модель — как PhotoDraw:
// координаты в долях 0..1, радиус — доля длинной стороны.
object PixelTool {

    class Stroke(val radius: Float, val points: List<Pair<Float, Float>>) {
        val size: Int get() = points.size
    }

    // радиусы кисти пикселизации: доли длинной стороны
    val RADII: List<Float> = listOf(0.02f, 0.045f, 0.09f)

    fun validRadius(r: Float): Boolean = r in RADII

    fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    // прореживание: как у PhotoDraw — путь не раздувается от микрожестов
    const val MIN_STEP = 0.004f

    fun shouldAppend(points: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        val last = points.lastOrNull() ?: return true
        val dx = x - last.first
        val dy = y - last.second
        return dx * dx + dy * dy >= MIN_STEP * MIN_STEP
    }

    fun undo(strokes: List<Stroke>): List<Stroke> = strokes.dropLast(1)

    const val MAX_STROKES = 200

    fun append(strokes: List<Stroke>, stroke: Stroke): List<Stroke> =
        (strokes + stroke).takeLast(MAX_STROKES)

    // степень пикселизации: сторона блока в пикселях исходника; блок 1/24
    // длинной стороны — читаемая «мозаика», как у Telegram
    const val BLOCK_FRACTION = 1f / 24f

    fun blockPx(width: Int, height: Int): Int =
        (maxOf(width, height) * BLOCK_FRACTION).toInt().coerceAtLeast(2)

    // грубая копия для отрисовки: даунскейл и растяжка без фильтра.
    // android.graphics — только здесь, JVM-тесты покрывают чистую часть
    fun pixelBitmap(source: android.graphics.Bitmap, blockPx: Int): android.graphics.Bitmap {
        val w = (source.width / blockPx).coerceAtLeast(1)
        val h = (source.height / blockPx).coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(source, w, h, false)
        return android.graphics.Bitmap.createScaledBitmap(
            small, source.width, source.height, false,
        )
    }

    // наложение: пикселизированная копия проявляется кружками вдоль
    // штрихов (BitmapShader в координатах исходника)
    fun renderOnto(source: android.graphics.Bitmap, strokes: List<Stroke>): android.graphics.Bitmap {
        val out = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        if (strokes.isEmpty()) return out
        val pixelated = pixelBitmap(out, blockPx(out.width, out.height))
        val long = maxOf(out.width, out.height).toFloat()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = false
            shader = android.graphics.BitmapShader(
                pixelated,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        val canvas = android.graphics.Canvas(out)
        for (st in strokes) {
            if (st.points.isEmpty()) continue
            val r = st.radius * long
            for (p in st.points) {
                canvas.drawCircle(p.first * out.width, p.second * out.height, r, paint)
            }
        }
        return out
    }
}
