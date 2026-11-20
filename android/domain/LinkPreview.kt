package chat.client.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Превью ссылок без сети: находим http(s)-адреса в тексте и показываем
// компактную карточку с доменом. Карточка ничего не подгружает.
// Домен разбирается HttpUrl: userinfo-трюк (https://google.com@evil.com)
// даёт честный хост evil.com, а не «google.com@evil.com» (A22).
object LinkPreview {

    // скобки разрешены внутри URL, но замыкающая — отрезается
    private val urlRegex = Regex("""https?://[^\s<>"'\\]+""")

    class Card(val domain: String, val url: String, val snippet: String)

    // диапазон URL в тексте: url — с обрезанной пунктуацией, card — null,
    // если хост не разобрался (текст защищён от разметки, но не кликается)
    class UrlRange(val range: IntRange, val url: String, val card: Card?)

    // первый URL в тексте или null
    fun from(text: String): Card? = all(text).firstOrNull()

    // все диапазоны http(s)-адресов; основа для all() и Markup.
    // Хвостовая пунктуация и маркеры разметки отрезаются от адреса
    fun ranges(text: String): List<UrlRange> {
        val out = ArrayList<UrlRange>()
        for (match in urlRegex.findAll(text)) {
            val raw = match.value
            val trimmedStart = raw.trimStart('(')
            val trimmed = trimmedStart.trimEnd('.', ',', ';', '!', '?', ')', '*', '_', '~', '`', '|')
            if (trimmed.isEmpty()) continue
            val shift = raw.length - trimmedStart.length
            val tail = raw.length - trimmed.length // обрезано с конца
            val start = match.range.first + shift
            val range = start until start + trimmed.length
            val domain = hostOf(trimmed)
            val card = domain?.let { Card(it, trimmed, text.trim().take(100)) }
            out += UrlRange(range, trimmed, card)
        }
        return out
    }

    // все URL в тексте (экран «Ссылки» в информации о чате)
    fun all(text: String): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (r in ranges(text)) {
            val card = r.card ?: continue
            if (seen.add(card.url)) out += card
        }
        return out
    }

    // честный хост: HttpUrl, фолбэк на ручной разбор; punycode не скрываем
    fun hostOf(url: String): String? {
        url.toHttpUrlOrNull()?.host?.let { return it.lowercase() }
        return try {
            val host = java.net.URI(url).host ?: return null
            if (host.contains('.')) host.lowercase() else null
        } catch (_: Exception) {
            null
        }
    }

    // есть ли в URL userinfo (https://google.com@evil.com) — предупредить.
    // Только authority: '@' в пути (medium.com/@user) — не userinfo
    fun hasUserinfo(url: String): Boolean = try {
        url.toHttpUrlOrNull()?.username?.isNotEmpty() == true
    } catch (_: Exception) {
        false
    }

    // омограф: xn---хост маскируется под известный домен (C4) —
    // предупредить как и в случае userinfo
    fun isPunycodeHost(url: String): Boolean = try {
        hostOf(url)?.startsWith("xn--") == true
    } catch (_: Exception) {
        false
    }
}
