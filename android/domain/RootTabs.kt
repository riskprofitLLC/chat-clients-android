package chat.client.domain

// Корневые табы нижней навигации-пилюли (D2): Чаты / Контакты /
// Настройки / Профиль. Чистая логика хранения выбранного таба — JVM-тесты.
object RootTabs {
    const val CHATS = "chats"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"

    val ALL = listOf(CHATS, CONTACTS, SETTINGS, PROFILE)

    // мусор из настроек читается как «Чаты»
    fun sanitize(raw: String?): String = if (raw != null && raw in ALL) raw else CHATS

    fun isValid(tab: String): Boolean = tab in ALL
}