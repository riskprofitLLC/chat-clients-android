package chat.client.domain

// Валидаторы полей ввода; регэкспы синхронны с сервером (go/auth.go)
object Validators {

    private val loginRe = Regex("^[a-z0-9_-]{3,32}$")
    private val emailRe = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")

    fun validLogin(login: String): Boolean = loginRe.matches(login)

    // id чата (журнал/бэкап): как имя файла стора — логин или grp_-группа
    fun validChatId(chatId: String): Boolean =
        chatId.length in 1..64 && Regex("^[A-Za-z0-9_-]+$").matches(chatId)

    fun validEmail(email: String): Boolean = emailRe.matches(email)

    fun validPassword(password: String): Boolean = password.length >= 6

    fun validCode(code: String): Boolean = Regex("^\\d{4}$").matches(code)
}
