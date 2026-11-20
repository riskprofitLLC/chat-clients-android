package chat.client.domain

// Фаза AD7 (аналог поиска в настройках у Telegram): статический каталог
// известных настроек и экранов; матчинг — по названию и ключевым словам,
// без учета регистра. Чистая логика — JVM-тесты.
object SettingsSearch {

    // пункт каталога: название, раздел, назначение (route-ключ) и ключи;
   // route == null — настройка живёт прямо на экране настроек, прыжок —
    // к её секции (скролл по якорю)
    class Entry(
        val title: String,
        val section: String,
        val route: String? = null,
        val sectionKey: String? = null,
        val keywords: List<String> = emptyList(),
    )

    // сопоставление: пустой запрос — нет результатов; слово запроса
    // матчится по ПРЕФИКСУ СЛОВА в названии или ключе (регистр не важен);
    // несколько слов — все обязаны совпасть; раздел — ключ, в поиске
    // не участвует (только для показа)
    private val wordSplit = Regex("\\s+")

    fun match(query: String, entries: List<Entry>): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(wordSplit).filter { it.isNotEmpty() }
        return entries.filter { e ->
            val hayWords = (listOf(e.title) + e.keywords)
                .flatMap { it.lowercase().split(wordSplit) }
                .filter { it.isNotEmpty() }
            words.all { w -> hayWords.any { h -> h.startsWith(w) } }
        }
    }

    // сортировка: точное совпадение названия, затем префикс названия,
    // затем остальное — как у Telegram сверху самые релевантные
    fun rank(query: String, found: List<Entry>): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return found
        return found.sortedWith(
            compareBy(
                { it.title.lowercase() != q },
                { !it.title.lowercase().startsWith(q) },
                { it.title.lowercase() },
            ),
        )
    }
}
