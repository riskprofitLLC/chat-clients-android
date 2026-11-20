package chat.client.domain

// AL4 (аналог Power saving / LiteMode у Telegram): режим
// энергосбережения — выкл / при низком заряде / всегда. Гасит
// анимации и автоплей (GIF-кадры, автоплей следующего голосового),
// экономя батарею; решение принимает один домен, UI только читает.
enum class PowerMode(val key: String) {
    OFF("off"),
    LOW("low"),
    ALWAYS("always");

    companion object {
        // мусор/null — прежнее поведение: выключено
        fun parse(raw: String?): PowerMode =
            entries.firstOrNull { it.key == raw } ?: OFF
    }
}

object PowerSaver {

    // граница «низкого» заряда, % — как порог стокового Android
    const val LOW_BATTERY_PCT = 20

    // активен ли режим сейчас; batteryPct null — уровень неизвестен
    // (нет BatteryManager) — «при низком» считать нельзя
    fun active(mode: PowerMode, batteryPct: Int?, charging: Boolean): Boolean = when (mode) {
        PowerMode.OFF -> false
        PowerMode.ALWAYS -> true
        PowerMode.LOW -> batteryPct != null && batteryPct <= LOW_BATTERY_PCT && !charging
    }
}
