package chat.client.domain

import org.json.JSONArray
import org.json.JSONException

// Просмотренные результаты опросов (Фаза AC10, аналог «View Results»
// у Telegram): после просмотра голосовать в опросе нельзя. Ключ —
// «chatId:ts» записи опроса; список с потолком (старейшие вытесняются).
// Чистый кодек — хранение в AppPrefs JSON-массивом, как недавние эмодзи.
object PollViewed {

    const val CAP = 400

    private val keyRe = Regex("[A-Za-z0-9_-]{1,64}:\\d{1,19}")

    // валидный ключ просмотренного опроса; мусор не храним
    fun keyOf(chatId: String, ts: Long): String? {
        val key = "$chatId:$ts"
        return if (keyRe.matches(key)) key else null
    }

    // разбор сохранённого списка; битый JSON и мусор — пустой список
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optString(i, "")
            if (keyRe.matches(e) && e !in out) out += e
            if (out.size >= CAP) break
        }
        return out
    }

    fun encode(list: List<String>): String {
        val arr = JSONArray()
        list.take(CAP).forEach { arr.put(it) }
        return arr.toString()
    }

    fun contains(list: List<String>, key: String): Boolean = key in list

    // отметка просмотра: наверх списка, потолок CAP
    fun push(list: List<String>, key: String): List<String> =
        if (!keyRe.matches(key)) list else (listOf(key) + list.filter { it != key }).take(CAP)
}
