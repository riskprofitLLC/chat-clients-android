package chat.client.domain

// Фаза AJ (аналог встроенного браузера у Telegram): политика ссылок
// для внутреннего просмотра. Чистая логика без Android — JVM-тесты.

object LinkPolicy {

    // только http/https попадают во встроенный просмотр; остальное
    // (intent:, file:, javascript:, content:, data: и мусор) — наружу
    // или отказ; сравнение схем без учёта регистра
    fun viewUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.take(schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(schemeEnd + 3)
        if (rest.isEmpty()) return null
        // хост обязан начинаться с буквы/цифры
        if (!rest[0].isLetterOrDigit()) return null
        return url
    }

    // хост для адресной строки: без userinfo, порта, пути; пусто — если
    // разобрать не удалось
    fun hostOf(url: String): String? {
        val safe = viewUrl(url) ?: return null
        val rest = safe.substring(safe.indexOf("://") + 3)
        var authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        // userinfo «user@host» отбрасываем — показываем только хост
        authority = authority.substringAfterLast('@')
        if (authority.isEmpty()) return null
        val host = authority.substringBefore(':')
        return host.ifEmpty { null }
    }
}
