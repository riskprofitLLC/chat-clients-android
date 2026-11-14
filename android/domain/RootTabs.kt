package chat.client.domain

// Корневые табы нижней навигации (D2): Чаты / Контакты / Настройки.
// Профиль — не таб, он доступен из шапки списка чатов. Чистая логика
// хранения выбранного таба — JVM-тесты.
object RootTabs {
    const val CHATS = "chats"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"

    val ALL = listOf(CHATS, CONTACTS, SETTINGS)

    // мусор из настроек читается как «Чаты»
    fun sanitize(raw: String?): String = if (raw != null && raw in ALL) raw else CHATS

    fun isValid(tab: String): Boolean = tab in ALL
}