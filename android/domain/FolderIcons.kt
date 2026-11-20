package chat.client.domain

// Фаза AD3 (аналог иконок папок у Telegram): набор эмодзи-иконок для
// табов папок. У Telegram — векторные значки (FolderBottomSheet); нам
// достаточно эмодзи — тот же выбор из фиксированного набора. Каждая
// иконка — один кластер (проверяется ChatFolders.sanitizeIcon).
object FolderIcons {

    val CHOICES: List<String> = listOf(
        "📁", "💼", "❤️", "⭐", "👤", "👥", "🤖", "🎮",
        "⚽", "📣", "🔔", "🎵", "🍀", "🏠", "✈️", "📚",
    )
}
