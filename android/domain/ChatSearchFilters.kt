package chat.client.domain

// Фаза AE10 (аналог фильтров поиска у Telegram): чипы поверх текстового
// запроса в списке чатов — «непрочитанные»/«группы»/«личные». Предикат
// чистый, без привязки к ChatSummary — тестируется на JVM.
enum class ChatSearchFilter { UNREAD, GROUPS, PRIVATE }

object ChatSearchFilters {

    // подходит ли чат под активный фильтр; null — фильтра нет
    fun matches(
        filter: ChatSearchFilter?,
        unread: Int,
        markedUnread: Boolean,
        isGroup: Boolean,
        isSelf: Boolean,
    ): Boolean = when (filter) {
        null -> true
        // «Избранное» — ни группа, ни личный чат: в фильтры не попадает
        ChatSearchFilter.UNREAD -> unread > 0 || markedUnread
        ChatSearchFilter.GROUPS -> isGroup
        ChatSearchFilter.PRIVATE -> !isGroup && !isSelf
    }
}
