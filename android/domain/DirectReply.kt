package chat.client.domain

// Подготовка текста быстрого ответа из уведомления (Фаза K1).
// Чистая функция: тесты на JVM без Android.
object DirectReply {

    // совпадает с лимитом композера диалога
    const val TEXT_LIMIT = 4096

    // пустой/пробельный ввод — не ответ; длинный обрезаем
    fun prepare(raw: CharSequence?, limit: Int = TEXT_LIMIT): String? {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return if (text.length > limit) text.take(limit) else text
    }
}
