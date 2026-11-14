package chat.client.domain

// Поиск по контактам (C3): без регистра, ё=е, по префиксам слов,
// опциональная транслитерация кириллица↔латиница. Чистая логика — JVM-тесты.
object ContactSearch {

    // нормализация строки запроса/поля: регистр, ё→е, сжатие пробелов
    fun normalize(s: String): String = s
        .lowercase()
        .replace('ё', 'е')
        .trim()
        .replace(Regex("\\s+"), " ")

    // транслитерация кириллицы в латиницу (для запросов латиницей)
    private val translit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l",
        'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
        'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    fun toLatin(s: String): String = buildString {
        for (ch in s) append(translit[ch] ?: ch.toString())
    }

    // совпадает ли поле с запросом: каждое слово запроса — префикс какого-то
    // слова поля (кириллица или её латинская транслитерация)
    fun matches(field: String, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        val words = normalize(field).split(' ')
        val latinField = toLatin(normalize(field))
        return q.split(' ').all { part ->
            part.isEmpty() || words.any { it.startsWith(part) } || latinField.contains(part)
        }
    }

    // отфильтровать по имени/телефону/e-mail
    fun filter(names: List<String>, query: String): List<Int> =
        names.indices.filter { matches(names[it], query) }

    // буквенный индекс для быстрой прокрутки: кириллица/латиница/# (прочее)
    fun sectionKey(name: String): String {
        val c = normalize(name).firstOrNull() ?: return "#"
        return when {
            c in 'а'..'я' -> c.uppercase()
            c in 'a'..'z' -> c.uppercase()
            else -> "#"
        }
    }
}
