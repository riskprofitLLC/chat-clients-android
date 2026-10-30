package chat.client.domain

// Санитайзер отображаемых имён (контакты, группы, bio, имена файлов):
// удаляет управляющие символы и bidi-override (U+202E и т.п.), схлопывает
// пробелы, ограничивает длину. Защита от спуфинга имён через невидимые символы.
object DisplayNameSanitize {

    private const val MAX_LEN = 64

    fun sanitize(raw: String?): String {
        if (raw == null) return ""
        val sb = StringBuilder(raw.length.coerceAtMost(MAX_LEN * 2))
        var lastSpace = false
        for (ch in raw) {
            val cat = ch.category
            // управляющие (Cc), bidi-контролы (Cf: U+202A-U+202E, U+2066-U+2069), приватные (Co)
            val bad = cat == CharCategory.CONTROL || cat == CharCategory.FORMAT ||
                cat == CharCategory.PRIVATE_USE || cat == CharCategory.UNASSIGNED
            if (bad) continue
            if (ch.isWhitespace()) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(ch)
                lastSpace = false
            }
            if (sb.length >= MAX_LEN) break
        }
        return sb.toString().trim()
    }
}
