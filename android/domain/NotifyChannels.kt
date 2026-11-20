package chat.client.domain

// Идентификаторы каналов уведомлений сообщений. Общий канал на каждый
// тон плюс отдельный канал чату с собственной важностью — звук и уровень
// канала после создания неизменяемы, поэтому «важность» живёт в самом id.
// Чистая логика без Android — проверяется JVM-тестами.
object NotifyChannels {

    const val CHANNEL_MESSAGES = "chat_messages" // базовый id — тон «системный»

    // префикс отдельного канала чата; суффикс — стабильный хеш chat_id,
    // один и тот же между запусками (иначе канал плодился бы заново)
    const val CHAT_CHANNEL_PREFIX = "chat_p_"

    // общий канал под выбранный тон; системный тон и отсутствие выбора —
    // базовый id (историческое имя первого канала приложения)
    fun messageChannelId(soundKey: String?): String =
        if (soundKey == null || soundKey == NotificationSound.SYSTEM) CHANNEL_MESSAGES
        else "chat_messages_$soundKey"

    // канал конкретного чата: своя важность — свой канал, иначе общий по тону
    fun chatChannelId(chatId: String, importanceKey: String?, soundKey: String?): String =
        if (!ChatImportance.needsOwnChannel(importanceKey)) messageChannelId(soundKey)
        else CHAT_CHANNEL_PREFIX + suffix(chatId)

    // это отдельный канал чата? нужно для чистки осиротевших каналов
    fun isChatChannel(channelId: String): Boolean =
        channelId.startsWith(CHAT_CHANNEL_PREFIX)

    private fun suffix(chatId: String): String =
        (chatId.hashCode().toLong() and 0xffffffffL).toString(16)
}

