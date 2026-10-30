package chat.client.domain

// AL-раунд (аналог Background dimming у Telegram): затемнение своих
// фото-обоев чата — пузыри остаются читаемыми на ярком фоне. Проценты
// чёрной вуали, дефолт — прежние 45% (никого не меняем молча).
object WallpaperDim {

    const val DEFAULT = 45
    const val MAX = 100

    // мусор и выходы за границы — к дефолту/краям
    fun sanitize(raw: Int): Int = if (raw in 0..MAX) raw else DEFAULT

    // альфа чёрной вуали для UI
    fun alpha(dim: Int): Float = sanitize(dim) / 100f
}
