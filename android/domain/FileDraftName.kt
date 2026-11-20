package chat.client.domain

// Фаза AE9 (аналог переименования файла при отправке у Telegram): имя
// вложения правится в превью до отправки. Чистая санитизация — без
// Android-зависимостей, тестируется на JVM.
object FileDraftName {

    // потолок имени: у дескриптора серверный лимит 256, берём с запасом
    const val MAX_LEN = 128

    // запрещённое в имени файла: разделители путей, управляющие символы
    private val badChars = Regex("[/\\\\\\u0000-\\u001F]")

    // нормализация введённого имени: пустое/чистка → прежнее имя;
    // разделители путей и управляющие символы вырезаются, пробелы
    // по краям срезаются, точки-в starters не оставляем
    fun sanitize(raw: String, current: String): String {
        val cleaned = badChars.replace(raw.trim(), "").trim().trimStart('.').trim()
        if (cleaned.isEmpty()) return current
        return cleaned.take(MAX_LEN)
    }
}
