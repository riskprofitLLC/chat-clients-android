package chat.client.data

import chat.client.domain.ChatFolder
import chat.client.domain.ChatFolders

// Раскладка списка чатов: сортировка (по образцу Telegram), сплит архива и
// фильтрация по папке. Чистая логика без Android — покрывается JVM-тестами.
object ChatListLayout {

    // порядок: закреплённые → черновики без истории → свежесть последнего
    // сообщения. Чат с черновиком и без сообщений всплывает к верху — как
    // в Telegram чаты-черновики не тонут под пустышками.
    // Фаза AG10: закрепы идут в ручном порядке pinOrder (id вне списка —
    // после перечисленных, по свежести). Пустой порядок — прежняя сортировка.
    fun sort(chats: List<ChatSummary>, pinOrder: List<String> = emptyList()): List<ChatSummary> {
        if (pinOrder.isEmpty()) return chats.sortedWith(freshComparator)
        val rank = pinOrder.withIndex().associate { (index, id) -> id to index }
        return chats.sortedWith(
            compareByDescending<ChatSummary> { it.pinned }
                .thenBy { if (it.pinned) rank[it.id] ?: Int.MAX_VALUE else 0 }
                .thenByDescending { it.draft.isNotBlank() && it.lastTs == null }
                .thenByDescending { it.lastTs ?: 0L },
        )
    }

    private val freshComparator = compareByDescending<ChatSummary> { it.pinned }
        .thenByDescending { it.draft.isNotBlank() && it.lastTs == null }
        .thenByDescending { it.lastTs ?: 0L }

    // основной список и архив по отдельности
    fun splitByArchive(chats: List<ChatSummary>): Pair<List<ChatSummary>, List<ChatSummary>> =
        chats.filterNot { it.archived } to chats.filter { it.archived }

    // суммарные непрочитанные архива — бейдж строки «Архив»;
    // вручную отмеченные считаются за единицу (как getUnreadCount в Telegram)
    fun archiveUnread(chats: List<ChatSummary>): Int =
        chats.sumOf { it.unread } + chats.count { unreadDot(it.unread, it.markedUnread) }

    // чаты папки: без архива, тот же порядок (пин/черновик/свежесть).
    // Вход уже отсортирован sort(): фильтрация его сохраняет, а закрепы
    // лишь поднимаются к верху — ручной порядок AG10 не теряется.
    // В папке нет секции «Архив» и приглашений — они на табе «Все чаты»
    fun inFolder(chats: List<ChatSummary>, folder: ChatFolder): List<ChatSummary> =
        chats.filter {
            !it.archived &&
                ChatFolders.matches(folder, it.id, it.isGroup, it.isSelf, it.unread)
        }.sortedByDescending { it.pinned }

    // бейдж непрочитанных таба папки (отмеченные вручную — за единицу)
    fun folderUnread(chats: List<ChatSummary>, folder: ChatFolder): Int {
        val inFolder = inFolder(chats, folder)
        return inFolder.sumOf { it.unread } + inFolder.count { unreadDot(it.unread, it.markedUnread) }
    }

    // точка-бейдж ручной отметки (Фаза O3, по образцу Telegram): показывается
    // у чата без счётчика непрочитанных; реальный счётчик важнее точки
    fun unreadDot(unread: Int, marked: Boolean): Boolean = marked && unread == 0
}
