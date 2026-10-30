package chat.client.domain

import org.json.JSONArray
import org.json.JSONObject

// Несколько закреплённых сообщений (Фаза W5, аналог pinned messages
// у Telegram): чат держит список закрепов, плашка показывает последний,
// лист — все с прыжками и открепом. Чистая логика без Android —
// тестируется на JVM; хранит JSON в ChatPrefs.
object PinnedMessages {

    const val MAX = 20 // потолок списка: старейшие вытесняются новыми

    // JSON: массив [{"i":ref,"to":ts}, …]
    fun encode(list: List<Pair<String, Long>>): String {
        val arr = JSONArray()
        for ((ref, ts) in list) {
            arr.put(JSONObject().put("i", ref).put("to", ts))
        }
        return arr.toString()
    }

    // разбор: мусор тихо даёт пустой список
    fun decode(raw: String?): List<Pair<String, Long>> {
        if (raw == null) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Pair<String, Long>>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val ref = item.optString("i")
                if (ref.isEmpty()) continue
                out += ref to item.optLong("to", 0L)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // добавить закреп в конец; уже закреплённое — не дублируется;
    // потолок: старейший вытесняется (как недавно использованные)
    fun add(list: List<Pair<String, Long>>, ref: String, ts: Long): List<Pair<String, Long>> {
        val filtered = list.filterNot { MessageRef.sameRef(it.first, ref) }
        val out = ArrayList(filtered)
        out += ref to ts
        return if (out.size > MAX) out.drop(out.size - MAX) else out
    }

    // убрать один закреп по ref
    fun remove(list: List<Pair<String, Long>>, ref: String): List<Pair<String, Long>> =
        list.filterNot { MessageRef.sameRef(it.first, ref) }

    // правка цели меняет ref: переносим закреп, плашка не исчезает
    fun repoint(list: List<Pair<String, Long>>, oldRef: String, newRef: String, ts: Long?): List<Pair<String, Long>> =
        list.map { (ref, at) ->
            if (MessageRef.sameRef(ref, oldRef)) newRef to (ts ?: at) else ref to at
        }

    // миграция единичного закрепа старого формата {"i","to"}
    fun fromSingle(raw: String?): Pair<String, Long>? {
        if (raw == null) return null
        return try {
            val json = JSONObject(raw)
            val ref = json.optString("i")
            if (ref.isEmpty()) null else ref to json.optLong("to", 0L)
        } catch (_: Exception) {
            null
        }
    }
}
