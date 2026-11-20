package chat.client.domain

// Локальные имена контактов (по образцу Telegram): пользователь задаёт
// собеседнику читаемое имя, видимое только на этом устройстве
object ContactName {

    const val MAX_LEN = 64

    // нормализация: трим, ограничение длины; пустое имя — нет (null)
    fun sanitize(raw: String): String? {
        val name = raw.trim().take(MAX_LEN)
        return name.ifEmpty { null }
    }

    // отображаемое имя: локальное имя поверх логина
    fun display(login: String, custom: String?): String =
        custom?.takeIf { it.isNotBlank() } ?: login
}
