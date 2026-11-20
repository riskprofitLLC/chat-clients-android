package chat.client.domain

// Фаза GB: уведомление входящего звонка в шторке. Постинг и отмена обязаны
// считать id одним путем — иначе ринг остаётся в шторке после ответа,
// отказа или конца звонка (смахнуть ongoing-уведомление нельзя).
object CallRing {
    fun notifId(chatId: String): Int = chatId.hashCode()
}
