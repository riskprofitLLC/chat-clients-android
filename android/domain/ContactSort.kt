package chat.client.domain

// Сортировка контактов (как в Telegram): по имени или по времени захода.
// Чистая логика без Android-зависимостей — покрывается JVM-тестами.
data class ContactSortRow(
    val name: String,
    val login: String?,
    val lastSeenMs: Long,
)

enum class ContactSortMode {
    NAME, LAST_SEEN,
}

object ContactSort {
    // пустое имя уходит в конец, остальные — без учёта регистра
    private val byName = compareBy<ContactSortRow> { it.name.isBlank() }
        .thenBy { it.name.lowercase() }

    // мусор в настройках читается как сортировка по имени
    fun sanitize(raw: String?): ContactSortMode = when (raw) {
        "last_seen" -> ContactSortMode.LAST_SEEN
        else -> ContactSortMode.NAME
    }

    fun key(mode: ContactSortMode): String = when (mode) {
        ContactSortMode.NAME -> "name"
        ContactSortMode.LAST_SEEN -> "last_seen"
    }

    fun comparator(mode: ContactSortMode): Comparator<ContactSortRow> = when (mode) {
        ContactSortMode.NAME -> byName
        // 0 — неизвестное время, вниз; при равенстве — по имени
        ContactSortMode.LAST_SEEN ->
            compareBy<ContactSortRow> { it.lastSeenMs <= 0L }
                .thenByDescending { it.lastSeenMs }
                .then(byName)
    }

    fun sort(rows: List<ContactSortRow>, mode: ContactSortMode): List<ContactSortRow> =
        rows.sortedWith(comparator(mode))
}
