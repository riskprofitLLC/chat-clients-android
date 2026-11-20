package chat.client.domain

// Фаза AD5 (аналог выбора размера фото у Telegram): большая сторона
// сжатого фото при отправке. Потолок по умолчанию — прежние 2048px;
// «оригинал» — отдельный путь «файл без сжатия» (Фаза V5), сюда не входит.
object PhotoQuality {

    val CHOICES: List<Int> = listOf(480, 720, 1080, 2048)
    const val DEFAULT = 2048

    // валидный размер из белого списка; мусор — умолчание
    fun sanitize(raw: Int): Int = if (raw in CHOICES) raw else DEFAULT

    // подпись чипа: как у Telegram — «короткой стороной», у нас —
    // по большой стороне кадра, подпись безразмерная
    fun label(target: Int): String = sanitize(target).toString()
}
