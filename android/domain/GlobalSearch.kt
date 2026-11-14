package chat.client.domain

// Глобальный поиск сообщений: подготовка сниппета и ограничение выдачи.
// Чистая логика без зависимостей от data-слоя — покрывается JVM-тестами.
object GlobalSearch {

    // сниппет попадания: без маркеров разметки, с ограничением длины
    fun snippet(text: String, query: String, maxLen: Int = 96): String {
        val plain = Markup.strip(text).trim()
        if (plain.length <= maxLen) return plain
        // стараемся показать окно вокруг первого попадания
        val at = plain.indexOf(query, ignoreCase = true)
        val start = if (at < 0) 0 else (at - maxLen / 3).coerceIn(0, plain.length - maxLen)
        val cut = plain.substring(start, (start + maxLen).coerceAtMost(plain.length)).trim()
        val prefix = if (start > 0) "…" else ""
        val suffix = if (start + maxLen < plain.length) "…" else ""
        return prefix + cut + suffix
    }

    // выдача: свежие сверху, не более perChat на чат и total итого
    fun <T> cap(
        hits: List<T>,
        chatOf: (T) -> String,
        tsOf: (T) -> Long,
        perChat: Int = 5,
        total: Int = 50,
    ): List<T> {
        val byChat = HashMap<String, Int>()
        val out = ArrayList<T>()
        for (hit in hits.sortedByDescending(tsOf)) {
            val chat = chatOf(hit)
            val n = byChat.getOrDefault(chat, 0)
            if (n >= perChat) continue
            byChat[chat] = n + 1
            out += hit
            if (out.size >= total) break
        }
        return out
    }
}
