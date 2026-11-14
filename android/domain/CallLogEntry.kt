package chat.client.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Запись журнала звонков для экрана «Звонки»: звонок из CallManager и
// «пропущенный» из репозитория пишутся в один локальный список.
data class CallLogEntry(
    val callId: String,
    val peer: String,
    val outgoing: Boolean,
    val status: String,
    val at: Long,
    val seconds: Int = 0,
)

// группа записей одного дня; dayStart — полночь этого дня
data class CallDayGroup(val dayStart: Long, val entries: List<CallLogEntry>)

// Чистая логика списка звонков: сортировка, группировка по дню, формат
// записи. UI только отображает — здесь всё покрывается JVM-тестами.
object CallLogList {

    const val STATUS_COMPLETED = "completed"
    const val STATUS_DECLINED = "declined"
    const val STATUS_CANCELED = "canceled"
    const val STATUS_MISSED = "missed"

    // свежие сверху, дубль по callId не показываем
    fun normalize(entries: List<CallLogEntry>): List<CallLogEntry> {
        val seen = HashSet<String>()
        return entries
            .sortedByDescending { it.at }
            .filter { seen.add(it.callId) }
    }

    fun groupByDay(entries: List<CallLogEntry>): List<CallDayGroup> =
        normalize(entries)
            .groupBy { dayStart(it.at) }
            .toList()
            .sortedByDescending { it.first }
            .map { (day, items) -> CallDayGroup(day, items) }

    fun dayStart(at: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = at
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // «1:05»; звонок без связи — null (длительность не показываем)
    fun formatDuration(seconds: Int): String? {
        if (seconds <= 0) return null
        return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("d MMMM", Locale.getDefault())

    fun formatTime(at: Long): String = synchronized(timeFmt) { timeFmt.format(Date(at)) }

    // «Сегодня» / «Вчера» / дата; now — опорный момент (тесты дают свой)
    fun dayLabel(dayStart: Long, now: Long): String {
        val today = dayStart(now)
        if (dayStart == today) return "Сегодня"
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        if (dayStart == dayStart(yesterday.timeInMillis)) return "Вчера"
        return synchronized(dayFmt) { dayFmt.format(Date(dayStart)) }
    }

    // статус записи текстом для строки списка
    fun statusText(entry: CallLogEntry): String = when (entry.status) {
        STATUS_MISSED -> if (entry.outgoing) "Нет ответа" else "Пропущенный"
        STATUS_DECLINED -> if (entry.outgoing) "Отклонён" else "Отклонённый"
        STATUS_CANCELED -> if (entry.outgoing) "Отменён" else "Отменённый"
        else -> formatDuration(entry.seconds) ?: "Завершённый"
    }
}