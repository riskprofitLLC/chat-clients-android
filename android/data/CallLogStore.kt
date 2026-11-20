package chat.client.data

import android.content.Context
import chat.client.domain.CallLogEntry
import org.json.JSONArray
import org.json.JSONObject

// Локальный журнал звонков для экрана «Звонки» (история живёт только на
// устройствах). Пишется из CallManager и при «пропущенных» записях
// репозитория; дубль по callId заменяется, длина ограничена.
class CallLogStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("call_log", Context.MODE_PRIVATE)

    @Synchronized
    fun add(entry: CallLogEntry) {
        val merged = all().filterNot { it.callId == entry.callId } + entry
        prefs().edit().putString(KEY, encode(merged)).apply()
    }

    @Synchronized
    fun all(): List<CallLogEntry> {
        val raw = prefs().getString(KEY, null) ?: return emptyList()
        return decode(raw)
    }

    @Synchronized
    fun clear() {
        prefs().edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val LIMIT = 200

        fun encode(entries: List<CallLogEntry>): String {
            val arr = JSONArray()
            for (e in entries.sortedByDescending { it.at }.take(LIMIT)) {
                arr.put(
                    JSONObject()
                        .put("id", e.callId)
                        .put("peer", e.peer)
                        .put("out", e.outgoing)
                        .put("st", e.status)
                        .put("at", e.at)
                        .put("sec", e.seconds),
                )
            }
            return arr.toString()
        }

        fun decode(raw: String): List<CallLogEntry> {
            val out = ArrayList<CallLogEntry>()
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        CallLogEntry(
                            callId = o.optString("id"),
                            peer = o.optString("peer"),
                            outgoing = o.optBoolean("out"),
                            status = o.optString("st"),
                            at = o.optLong("at"),
                            seconds = o.optInt("sec"),
                        ),
                    )
                }
            } catch (_: Exception) {
                // битый журнал не критичен — отдаём что разобрали
            }
            return out.filter { it.callId.isNotEmpty() && it.peer.isNotEmpty() }
        }
    }
}