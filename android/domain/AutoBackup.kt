package chat.client.domain

// AI5 (аналог резервного копирования Telegram): автоматические копии
// журнала по расписанию в папку SAF. Чистая логика без Android —
// имя файла, ротация и периоды тестируются на JVM.
object AutoBackup {

    // периоды, дни; 0 — выключено
    const val MODE_OFF = 0
    const val MODE_DAILY = 1
    const val MODE_WEEKLY = 7

    // сколько последних копий храним в папке
    const val KEEP = 7

    val MODES = listOf(MODE_OFF, MODE_DAILY, MODE_WEEKLY)

    fun validMode(days: Int): Boolean = days in MODES

    // имя файла: фиксированная ширина — лексикографическая сортировка
    // совпадает с хронологической (ротация не читает метки)
    fun fileName(nowMs: Long): String {
        val cal = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault())
        return "chat-backup-" + java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").format(cal) + ".bin"
    }

    // ротация: имена копий папки; на удаление — всё сверх KEEP младших
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> {
        val sorted = names
            .filter { it.startsWith("chat-backup-") && it.endsWith(".bin") }
            .sortedDescending()
        return sorted.drop(keep)
    }

    // пароль автобэкапа: тот же порог, что у ручного экспорта
    fun validPassword(pw: String): Boolean = pw.length >= 6

    // A5: для бэкапа с секретом идентичности — длиннее: 6-символьный пароль
    // не держит удар GPU при PBKDF2-600k
    const val IDENTITY_MIN_PASSWORD = 12

    // секрет идентичности в автобэкап — только по явному согласию И при
    // длинном пароле (гейт воркера, вынесен для теста)
    fun includeIdentityAllowed(consent: Boolean, password: String): Boolean =
        consent && password.length >= IDENTITY_MIN_PASSWORD

    // индексный интервал между прогонами воркера — читается настройками
    fun intervalDays(mode: Int): Long = when (mode) {
        MODE_DAILY -> 1L
        MODE_WEEKLY -> 7L
        else -> 0L
    }
}
