package chat.client.domain

// Экспорт чата с медиа (Фаза AC8, аналог «Export with media» у Telegram):
// выборка имен файлов внутри ZIP-архива без коллизий. Чистая логика —
// тестируется на JVM; сами байты пишет ViewModel через SAF.
object ChatExportMedia {

    // расширение по mime для вложений без имени (голосовые, фото)
    fun extensionOf(name: String?, mime: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        if (!ext.isNullOrEmpty() && ext.length <= 12 && ext.all { it.isLetterOrDigit() }) return ext
        return when {
            mime == null -> "bin"
            mime.startsWith("image/gif") -> "gif"
            mime.startsWith("image/") -> "jpg"
            mime.startsWith("video/") -> "mp4"
            mime.startsWith("audio/") -> "m4a"
            else -> "bin"
        }
    }

    // безопасное имя из оригинала: без пути, посторонние символы — в «_»
    fun sanitizeName(name: String?): String {
        val base = (name ?: "").substringAfterLast('/').substringBeforeLast('.')
        val cleaned = base.map { if (it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ') it else '_' }
            .joinToString("")
            .trim(' ', '_')
            .take(48)
        return cleaned.ifEmpty { "file" }
    }

    // имя файла в архиве: media/0001_имя.ext; при коллизии — суффикс _2
    fun entryName(seq: Int, original: String?, mime: String?, used: Set<String>): String {
        val ext = extensionOf(original, mime)
        val base = "%04d_%s".format(seq, sanitizeName(original))
        // проверяем готовое имя с префиксом — иначе коллизия не ловилась
        var candidate = "media/$base.$ext"
        var n = 2
        while (candidate in used) {
            candidate = "media/${base}_$n.$ext"
            n++
        }
        return candidate
    }
}
