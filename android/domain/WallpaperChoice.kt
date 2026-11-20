package chat.client.domain

// Фаза T2: обои конкретного чата. FOLLOW — наследовать глобальный выбор
// из настроек; 0 — узор, 1 — однотонный (те же значения, что и в AppPrefs).
// Мусор в prefs (повреждённое значение) сворачивается обратно в FOLLOW.
object WallpaperChoice {
    const val FOLLOW = -1
    const val PATTERN = 0
    const val SOLID = 1

    const val CUSTOM = 2

    fun normalize(value: Int): Int = when (value) {
        FOLLOW, PATTERN, SOLID, CUSTOM -> value
        else -> FOLLOW
    }

    // итоговые обои чата: локальный выбор перекрывает глобальный
    fun effective(perChat: Int, global: Int): Int =
        if (perChat != FOLLOW) perChat else global
}
