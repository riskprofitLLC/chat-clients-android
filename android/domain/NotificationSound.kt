package chat.client.domain

// Звук уведомлений (Фаза AA4, аналог выбора рингтона у Telegram):
// системный, без звука или один из встроенных тонов. Ключи — строкой
// в настройках; соответствие ресурсов — в UI-слое (res/raw).
object NotificationSound {

    const val SYSTEM = "system"
    const val NONE = "none"
    const val SOFT = "soft"
    const val BELL = "bell"
    const val POP = "pop"

    val ALL = listOf(SYSTEM, NONE, SOFT, BELL, POP)

    // безопасный разбор ключа: неизвестное — системный
    fun parse(raw: String?): String =
        if (raw != null && raw in ALL) raw else SYSTEM

    // допустим ли ключ как per-chat (null = как в настройках)
    fun isValidKey(raw: String?): Boolean = raw == null || raw in ALL
}
