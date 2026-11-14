package chat.client.domain

// Табы типов результата глобального поиска: чистая классификация попадания
// по его содержимому (медиа/файл/ссылка), без привязки к data-слою и UI.
// Чаты — не попадание, а секция результатов: её таб выбирается в UI.
enum class SearchTab { CHATS, MESSAGES, MEDIA, LINKS, FILES }

object SearchResultKind {

    // ссылка в тексте — http(s):// или www. в начале слова (как HistoryNav)
    private val linkRe = Regex("""(?i)(?<![\p{L}\p{N}])(https?://|www\.)""")

    fun hasLink(text: String?): Boolean = text != null && linkRe.containsMatchIn(text)

    // медиа — фото/видео/GIF (type=media), файлы — документы (type=file)
    fun classify(mediaType: String?, mime: String?, text: String?): SearchTab = when {
        mediaType == "media" && isVisual(mime) -> SearchTab.MEDIA
        mediaType == "file" -> SearchTab.FILES
        hasLink(text) -> SearchTab.LINKS
        else -> SearchTab.MESSAGES
    }

    private fun isVisual(mime: String?): Boolean =
        mime?.startsWith("image/") == true || mime?.startsWith("video/") == true || mime == "image/gif"

    // сообщения под активным табом: null и «Сообщения» — вся выдача,
    // «Чаты» — только секция чатов, остальные табы фильтруют по типу
    fun <T> messagesFor(tab: SearchTab?, hits: List<T>, kindOf: (T) -> SearchTab): List<T> = when (tab) {
        null, SearchTab.MESSAGES -> hits
        SearchTab.CHATS -> emptyList()
        else -> hits.filter { kindOf(it) == tab }
    }

    // секция найденных чатов видна без таба и на табе «Чаты»
    fun chatsShown(tab: SearchTab?): Boolean = tab == null || tab == SearchTab.CHATS
}