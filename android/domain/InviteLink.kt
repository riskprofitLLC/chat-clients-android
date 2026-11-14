package chat.client.domain

// Ссылка-приглашение (Фаза AB9, по образцу tg://-ссылок Telegram):
// https-адрес сайта беты с логином в фрагменте — фрагмент не ходит на
// сервер, сайт не нужен для открытия чата. Внутри приложения те же
// ссылки открываются deep link'ом; QR-формат «chat:<логин>» принимается
// тоже — единая точка разбора.
object InviteLink {

    const val SITE = "https://ednar.duckdns.org/chat/"

    // персональная ссылка: логин в фрагменте, кликабельна в браузере
    fun linkFor(login: String): String = SITE + "#u=" + login

    // логин из ссылки/QR-формата; null — посторонний адрес.
    // Понимает: https://…/chat/#u=логин, …/chat/?u=логин, chat:логин
    fun parse(raw: String?): String? {
        if (raw == null) return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        // QR-контакт: тот же разбор, что у сканера
        QrContact.parse(text)?.let { return it }
        // только наш сайт: чужие ссылки не открываем как чаты
        val lower = text.lowercase()
        if (!lower.startsWith("https://ednar.duckdns.org/chat/")) return null
        // параметр ищем ТОЛЬКО во фрагменте или запросе и сверяем имя ключа —
        // прежний поиск подстроки «u=» срабатывал на «menu=…»
        val tail = lower.substringAfter('#', "").ifEmpty { lower.substringAfter('?', "") }
        if (tail.isEmpty()) return null
        val login = tail.split('&')
            .firstOrNull { it.substringBefore('=') == "u" }
            ?.substringAfter('=', "")
            ?.trim()
            ?: return null
        // группы по ссылке не открываем: id группы — приватный
        if (login.startsWith("grp_")) return null
        return if (Validators.validLogin(login)) login else null
    }
}
