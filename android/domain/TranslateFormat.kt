package chat.client.domain

// GE3 (аналог Translate message у Telegram): локальный перевод текста
// сообщения. Перевод НЕ уходит в сеть как сообщение — только движок
// on-device + локальный кэш. Чистая логика — JVM-тесты.
object TranslateFormat {

    const val MAX_TEXT = 4000 // потолок текста на перевод
    private const val MAX_CACHED = 500 // записей в кэше

    // ключ кэша: ref сообщения + язык
    fun cacheKey(ref: String, lang: String): String = "$ref|$lang"

    // что переводить: текст/ответ, обрезка по потолку
    fun source(kind: MessageCodec.Kind): String? = when (kind) {
        is MessageCodec.Kind.Text -> kind.text
        is MessageCodec.Kind.Reply -> kind.text
        else -> null
    }?.let { Markup.strip(it).trim() }?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)

    // язык — короткий tag ("ru", "en"); мусор → null
    fun sanitizeLang(tag: String): String? {
        val t = tag.trim().lowercase().take(8)
        return t.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }
    }
}
