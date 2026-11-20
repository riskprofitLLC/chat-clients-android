package chat.client.domain

import org.json.JSONObject
import java.time.ZoneId

// GE6 (аналог BusinessHours у Telegram): часы работы в профиле — по одному
// интервалу «с HH:MM до HH:MM» на каждый день недели (ISO: пн=1..вс=7).
// Ездит опциональным полем "bh" внутри TYPE_BIO-конверта: старые клиенты
// его игнорируют. Чистая логика без Android — тестируется на JVM.
object BusinessHours {

    data class Day(val from: Int, val to: Int) // минуты от полуночи, from < to

    class Hours(val days: Map<Int, Day>) // день ISO 1..7 → интервал

    sealed interface Status {
        data class OpenUntil(val to: Int) : Status      // открыто сейчас, закроется в to
        data class OpensAt(val from: Int) : Status      // сегодня закрыто, откроется в from
        object Closed : Status                          // сегодня интервалов нет / уже прошли
    }

    // валидация: день 1..7, 0 <= from < to < 24*60; мусор отбрасывается
    fun sanitize(days: Map<Int, Day>): Hours {
        val clean = days.filter { (d, iv) ->
            d in 1..7 && iv.from in 0 until 24 * 60 && iv.to in 0 until 24 * 60 && iv.from < iv.to
        }
        return Hours(clean)
    }

    fun isEmpty(hours: Hours?): Boolean = hours == null || hours.days.isEmpty()

    // статус на момент (день ISO, минута суток)
    fun status(hours: Hours?, dayIso: Int, minute: Int): Status {
        if (hours == null) return Status.Closed
        val today = hours.days[dayIso] ?: return Status.Closed
        val m = minute.coerceIn(0, 24 * 60 - 1)
        return if (m in today.from until today.to) {
            Status.OpenUntil(today.to)
        } else if (m < today.from) {
            Status.OpensAt(today.from)
        } else {
            Status.Closed
        }
    }

    fun statusNow(hours: Hours?, zone: ZoneId = ZoneId.systemDefault(), now: Long): Status {
        if (hours == null) return Status.Closed
        val local = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
        return status(hours, local.dayOfWeek.value, local.hour * 60 + local.minute)
    }

    fun dayName(dayIso: Int, zone: ZoneId = ZoneId.systemDefault(), locale: java.util.Locale = java.util.Locale.getDefault()): String =
        java.time.DayOfWeek.of(dayIso.coerceIn(1, 7)).getDisplayName(java.time.format.TextStyle.SHORT, locale)

    // сериализация в вид для поля "bh" конверта: {"1":[540,1080],...}
    fun toJson(hours: Hours): String {
        val o = JSONObject()
        for ((d, iv) in hours.days) o.put(d.toString(), org.json.JSONArray().put(iv.from).put(iv.to))
        return o.toString()
    }

    // разбор поля "bh"; null — поля нет или оно битое (тогда просто нет часов)
    fun fromJson(json: String?): Hours? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val days = HashMap<Int, Day>()
            for (key in o.keys()) {
                val d = key.toIntOrNull() ?: continue
                val arr = o.optJSONArray(key) ?: continue
                if (arr.length() != 2) continue
                days[d] = Day(arr.getInt(0), arr.getInt(1))
            }
            val s = sanitize(days)
            if (s.days.isEmpty()) null else s
        } catch (_: Exception) {
            null
        }
    }

    // minute → «H:MM»
    fun formatMinute(minute: Int): String {
        val m = minute.coerceIn(0, 24 * 60 - 1)
        return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    // «H:MM» → минуты; null — не парсится (reuse AutoDark)
    fun parseMinute(hhmm: String): Int? = AutoDark.parseMinute(hhmm)
}
