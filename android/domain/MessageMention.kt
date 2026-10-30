package chat.client.domain

// Упоминание @логина в тексте сообщения (аналог mention-флага у
// Telegram): нужно, чтобы упоминание в мьютнутой группе всё равно
// дёргало уведомление. Логин — символы [a-zA-Z0-9_-]; '@' обязан
// начинать «слово» (перед ним — не часть логина), после совпадения
// не должно быть продолжения логина. Матч нечувствителен к регистру.
object MessageMention {

    private val loginChars = Regex("[a-zA-Z0-9_-]")

    fun contains(text: String, login: String): Boolean {
        if (login.isEmpty()) return false
        val needle = login.lowercase()
        var i = text.indexOf('@')
        while (i >= 0) {
            // '@' внутри слова (например «a@b») — не упоминание
            val prev = text.getOrNull(i - 1)
            if (i == 0 || !loginChars.matches(prev.toString())) {
                val rest = text.substring(i + 1)
                if (rest.length >= needle.length) {
                    val head = rest.substring(0, needle.length)
                    val tail = rest.getOrNull(needle.length)
                    if (head.lowercase() == needle &&
                        (tail == null || !loginChars.matches(tail.toString()))
                    ) return true
                }
            }
            i = text.indexOf('@', i + 1)
        }
        return false
    }

    // текст сообщения, в котором ищем упоминание: Text и Reply
    fun mentions(kind: MessageCodec.Kind, login: String): Boolean = when (kind) {
        is MessageCodec.Kind.Text -> contains(kind.text, login)
        is MessageCodec.Kind.Reply -> contains(kind.text, login)
        else -> false
    }

    // AH6 (аналог @everyone у Telegram): токен @all в тексте —
    // упоминание всех участников группы; пробивает мьют каждому.
    // Логин «all» при этом тоже работает сам по себе (пользователь
    // с таким логином упомянут по общим правилам)
    const val ALL_TOKEN = "all"

    fun containsAll(text: String): Boolean = contains(text, ALL_TOKEN)

    fun mentionsAll(kind: MessageCodec.Kind): Boolean = when (kind) {
        is MessageCodec.Kind.Text -> containsAll(kind.text)
        is MessageCodec.Kind.Reply -> containsAll(kind.text)
        else -> false
    }
}
