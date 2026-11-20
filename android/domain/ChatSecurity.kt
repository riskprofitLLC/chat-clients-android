package chat.client.domain

// AK10 (аналог защиты секретных чатов у Telegram): запрет скриншотов
// для отдельного чата — FLAG_SECURE на окно, пока чат открыт. Значение
// на чат: наследовать глобальную настройку, запретить или разрешить
// поверх неё. Три состояния — как WallpaperChoice.
object ChatSecurity {

    const val FOLLOW = -1 // наследовать глобальную настройку
    const val OFF = 0    // разрешить скриншоты поверх глобального запрета
    const val ON = 1     // запретить, даже если глобально разрешено

    fun normalize(value: Int): Int = when (value) {
        FOLLOW, OFF, ON -> value
        else -> FOLLOW
    }

    // итоговый запрет для открытого чата: override решает, FOLLOW — глобальный
    fun effective(perChat: Int, globalSecure: Boolean): Boolean = when (normalize(perChat)) {
        ON -> true
        OFF -> false
        else -> globalSecure
    }
}
