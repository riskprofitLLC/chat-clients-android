package chat.client.domain

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Прогресс прослушивания голосовых (по образцу запоминания позиции в
// Telegram VoiceMessagePlayer): голубая точка у непрослушанного входящего
// и продолжение прерванного с сохранённого места (Фаза AB3).
// Хранение — JSON-массив [{"id":..,"p":ms,"h":0|1,"t":unixMs}, ...]
// в AppPrefs; список — в порядке давности использования (LRU),
// старейшие вытесняются при потолке MAX.
object VoiceProgress {

    const val MAX = 500

    // одна запись: позиция в мс, прослушана ли до конца, момент записи
    class Entry(
        val id: String,
        val positionMs: Int,
        val heard: Boolean,
        val at: Long,
    )

    // разбор сохранённого списка; битый JSON и мусор — пустой список
    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<Entry>(minOf(arr.length(), MAX))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            out += Entry(
                id,
                o.optInt("p", 0).coerceAtLeast(0),
                o.optInt("h", 0) == 1,
                o.optLong("t", 0L),
            )
            if (out.size >= MAX) break
        }
        return out
    }

    fun encode(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.takeLast(MAX).forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("p", e.positionMs)
                    .put("h", if (e.heard) 1 else 0)
                    .put("t", e.at),
            )
        }
        return arr.toString()
    }

    // обновить/вставить запись: новая уходит в конец списка (свежая),
    // старейшие с начала вытесняются при потолке max
    fun upsert(list: List<Entry>, entry: Entry, max: Int = MAX): List<Entry> =
        (list.filter { it.id != entry.id } + entry).takeLast(max)

    fun find(list: List<Entry>, id: String): Entry? = list.firstOrNull { it.id == id }

    fun positionFor(list: List<Entry>, id: String): Int = find(list, id)?.positionMs ?: 0

    fun heard(list: List<Entry>, id: String): Boolean = find(list, id)?.heard == true
}
