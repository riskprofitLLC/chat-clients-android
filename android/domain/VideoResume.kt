package chat.client.domain

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Позиции просмотра видео (Фаза AF2, по образцу запоминания позиции
// у VoiceProgress): прерванный фильм продолжается с сохранённого места.
// Хранение — JSON-массив [{"id":..,"p":ms,"t":unixMs}] в AppPrefs;
// LRU: новая запись в конец, старейшие вытесняются при потолке MAX.
object VideoResume {

    const val MAX = 200

    // хвост, который должен остаться, чтобы продолжение имело смысл
    const val MIN_TAIL_MS = 5_000

    // одна запись: позиция в мс, момент записи
    class Entry(
        val id: String,
        val positionMs: Int,
        val at: Long,
    )

    // разбор сохранённого списка; битый JSON/мусор — пустой список
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

    // удаление записи (просмотрено до конца)
    fun clear(list: List<Entry>, id: String): List<Entry> = list.filter { it.id != id }

    // решение о продолжении: позиция строго дальше MIN_TAIL_MS от начала
    // И до конца остаётся больше MIN_TAIL_MS; durationMs <= 0 — не мешает
    fun shouldResume(positionMs: Int, durationMs: Int): Boolean {
        if (positionMs <= MIN_TAIL_MS) return false
        if (durationMs <= 0) return true
        return durationMs - positionMs > MIN_TAIL_MS
    }
}
