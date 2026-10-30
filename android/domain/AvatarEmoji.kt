package chat.client.domain

// Фаза AE7 (аналог эмодзи-аватара у Telegram): группа может носить
// эмодзи вместо фото — без загрузки блоба, едет в поле ava конверта.
object AvatarEmoji {

    // фиксированный набор выбора: один эмодзи-кластер в каждом
    val CHOICES: List<String> = listOf(
        "🐱", "🐶", "🦊", "🐼", "🦁", "🐸", "🦄", "🐝",
        "🌸", "🔥", "⚡", "🌙", "⭐", "🎧", "🎮", "☕",
        "🚀", "🏝️", "💬", "👑",
    )

    // санитизация: ровно один кластер из набора допустимых кодировок;
    // мусор → null (конверт с таким ava не создаём)
    fun sanitize(raw: String): String? {
        val cluster = EmojiOnly.firstCluster(raw)
        return if (cluster.isNotEmpty() && EmojiOnly.count(raw) == 1) cluster else null
    }
}
