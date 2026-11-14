package chat.client.domain

// Тон кожи эмодзи (Фаза AG4, аналог выбора тона в панели эмодзи Telegram):
// 0 — без тона, 1..5 — модификаторы U+1F3FB..U+1F3FF. Тон добавляется
// к поддерживаемым базам; модификатор ставится ДО селектора FE0F.
// Чистая логика — JVM-тесты; палитра свотчей — в UI.
object EmojiTone {

    const val NONE = 0 // без модификатора
    const val FIRST = 1 // самый светлый тон (U+1F3FB)
    const val LAST = 5 // самый тёмный тон (U+1F3FF)

    // селектор представления (emoji presentation selector)
    private const val VS = "\uFE0F"

    // модификаторы тона по номеру тона; индекс 0 — пусто
    private val MODIFIERS = listOf(
        "",
        "🏻", // U+1F3FB
        "🏼", // U+1F3FC
        "🏽", // U+1F3FD
        "🏾", // U+1F3FE
        "🏿", // U+1F3FF
    )

    // базы с поддержкой тона: ладони, жесты, люди, части тела.
    // Профессии и семьи (ZWJ-цепочки) тон не принимают.
    // Варианты с FE0F продублированы — панель и поиск держат их раздельно.
    val SUPPORTED: Set<String> = setOf(
        // ладони и жесты
        "👋", "🤚", "🖐", "✋", "🖖", "👌",
        "✌", "✌️", "🤞", "🤟", "🤘", "🤙",
        "👈", "👉", "👆", "👇", "☝", "☝️",
        "👍", "👎", "✊", "👊", "🤛", "🤜",
        "👏", "🙌", "👐", "🤲", "🙏",
        "✍", "✍️", "💅", "🤳", "💪", "🦵", "🦶",
        // части тела
        "👂", "👃",
        // люди
        "👶", "👧", "👦", "👩", "👨", "👴", "👵", "🧓", "👼",
        // жесты
        "🙋", "🙆", "🙅", "🙇", "🙎",
        // активность
        "🚶", "🏃", "💃", "🕺", "🧗", "🤸", "🤾",
        "🏌", "🏌️", "🏄", "🚴", "🚵", "🏊",
    )

    // санитизация тона: вне 0..5 — «без тона»
    fun sanitize(tone: Int): Int = if (tone in NONE..LAST) tone else NONE

    // применить тон к базе; чужой эмодзи, неверный тон — как есть
    fun apply(tone: Int, emoji: String): String {
        if (tone !in FIRST..LAST) return emoji
        val hasSelector = emoji.endsWith(VS)
        val base = if (hasSelector) emoji.dropLast(1) else emoji
        if (base !in SUPPORTED) return emoji
        val modifier = MODIFIERS[tone]
        // модификатор — перед селектором представления
        return if (hasSelector) base + modifier + VS else base + modifier
    }

    // убрать модификатор тона — вернуть базу
    fun strip(emoji: String): String {
        if (emoji.isEmpty()) return emoji
        val out = StringBuilder(emoji.length)
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            if (cp in 0x1F3FB..0x1F3FF) {
                i += Character.charCount(cp)
                continue
            }
            out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
