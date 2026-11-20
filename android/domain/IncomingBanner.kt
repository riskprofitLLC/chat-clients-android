package chat.client.domain

// B11/D3: чат для in-app баннера — с наибольшим числом новых сообщений.
// При равенстве берём больший id: выбор детерминирован (важно для тестов
// и чтобы баннер не «мигал» между чатами с одинаковым счётчиком).
fun topIncomingChat(byChat: Map<String, Int>): Pair<String, Int>? =
    byChat.entries
        .filter { it.value > 0 }
        .maxWithOrNull(compareBy({ it.value }, { it.key }))
        ?.let { it.key to it.value }
