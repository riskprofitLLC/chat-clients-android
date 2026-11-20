package chat.client.domain

// Фаза AE8 (аналог инструмента «Текст» в фоторедакторе Telegram):
// надпись поверх снимка перед отправкой. Позиция — доли изображения
// (якорь в центре надписи), размер шрифта — доля длинной стороны;
// единая модель для превью и наложения на битмап, как у PhotoDraw.
object PhotoText {

    // потолок длины надписи
    const val MAX_LEN = 100

    // размеры шрифта: доли длинной стороны изображения
    val SIZES: List<Float> = listOf(0.04f, 0.06f, 0.09f, 0.13f)

    fun validSize(s: Float): Boolean = s in SIZES

    // санитизация ввода: управляющие символы и переводы строк — в пробел,
    // пустой результат означает «надписи нет»
    fun sanitize(raw: String): String =
        raw.replace(Regex("[\\u0000-\\u001F]"), " ").trim().take(MAX_LEN)

    fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    // сдвиг якоря перетаскиванием: приращение жеста в пикселях экрана
    // переводим в доли изображения
    fun drag(pos: Pair<Float, Float>, dxPx: Float, dyPx: Float, wPx: Float, hPx: Float): Pair<Float, Float> {
        if (wPx <= 0f || hPx <= 0f) return pos
        return clamp01(pos.first + dxPx / wPx) to clamp01(pos.second + dyPx / hPx)
    }

    // наложение надписи на битмап (android.graphics): тот же якорь-центр,
    // что у превью; ARGB_8888-копия исходника
    fun renderOnto(
        source: android.graphics.Bitmap,
        text: String,
        color: Long,
        sizeFrac: Float,
        x: Float,
        y: Float,
    ): android.graphics.Bitmap {
        val out = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val long = maxOf(out.width, out.height).toFloat()
        val paint = android.graphics.Paint().apply {
            this.color = color.toInt()
            textSize = sizeFrac * long
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        // якорь — центр надписи: baseline опускаем на пол-высоты глифов
        val baseline = y * out.height - (fm.ascent + fm.descent) / 2f
        android.graphics.Canvas(out).drawText(text, x * out.width, baseline, paint)
        return out
    }
}
