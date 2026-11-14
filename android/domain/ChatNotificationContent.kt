package chat.client.domain

// AK4 (аналог per-chat message preview у Telegram — content_preview_):
// содержание уведомлений на чат: наследовать глобальную настройку,
// показывать или скрывать поверх неё.
object ChatNotificationContent {

    const val FOLLOW = 0 // наследовать глобальную
    const val SHOW = 1  // показывать текст даже при глобальном скрытии
    const val HIDE = 2  // скрывать текст для этого чата

    fun normalize(value: Int): Int = when (value) {
        FOLLOW, SHOW, HIDE -> value
        else -> FOLLOW
    }

    // итоговое скрытие текста уведомления чата: override решает
    fun hideText(perChat: Int, globalHide: Boolean): Boolean = when (normalize(perChat)) {
        HIDE -> true
        SHOW -> false
        else -> globalHide
    }
}
