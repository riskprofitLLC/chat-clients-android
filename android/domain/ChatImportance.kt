package chat.client.domain

// Важность уведомлений отдельного чата (аналог каналов важности у
// Telegram): «как в настройках», «важный» (heads-up и звук) или «тихий»
// (без heads-up). Не путать с тоном AB4 — своя важность даёт чату свой
// канал уведомлений.
object ChatImportance {

    const val FOLLOW = "follow"
    const val HIGH = "high"
    const val LOW = "low"

    val ALL = listOf(FOLLOW, HIGH, LOW)

    // безопасный разбор ключа: неизвестное — «как в настройках»
    fun parse(raw: String?): String =
        if (raw != null && raw in ALL) raw else FOLLOW

    // допустим ли ключ как per-chat (null = как в настройках)
    fun isValidKey(raw: String?): Boolean = raw == null || raw in ALL

    // своя важность требует отдельного канала уведомлений
    fun needsOwnChannel(raw: String?): Boolean = parse(raw) != FOLLOW
}

