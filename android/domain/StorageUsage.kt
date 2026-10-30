package chat.client.domain

// Хранилище (Фаза S2, аналог Storage usage у Telegram): чистая логика
// свода размеров и права чистки медиа по чатам — без Android-зависимостей.

// одна строка «по чатам»: id медиа чата + точные байты его медиа и журнала
class ChatUsage(
    val chatId: String,
    val mediaIds: Set<String>,
    val mediaBytes: Long,
    val journalBytes: Long,
) {
    val mediaCount: Int get() = mediaIds.size
    val totalBytes: Long get() = mediaBytes + journalBytes
}

// свод по категориям; chats — по убыванию суммы
class StorageUsage(
    val mediaBytes: Long,
    val historyBytes: Long,
    val stickersBytes: Long,
    val queueBytes: Long,
    val chats: List<ChatUsage>,
) {
    val totalBytes: Long
        get() = mediaBytes + historyBytes + stickersBytes + queueBytes
}

object StorageUsageLogic {

    // сортировка строк по чатам: больше байтов — выше; равные — по имени
    fun sorted(chats: List<ChatUsage>): List<ChatUsage> =
        chats.sortedWith(
            compareByDescending<ChatUsage> { it.totalBytes }.thenBy { it.chatId },
        )

    // id медиа, которые можно чистить у чата: только те, что НЕ встречаются
    // ни в одном другом чате (пересылка делит один блоб на два чата —
    // удаление файла сломало бы второй чат)
    fun deletableFor(chatId: String, chats: List<ChatUsage>): Set<String> {
        val own = chats.firstOrNull { it.chatId == chatId }?.mediaIds ?: return emptySet()
        val others = chats.filterNot { it.chatId == chatId }.flatMap { it.mediaIds }.toHashSet()
        return own.filterNot { it in others }.toSet()
    }
}
