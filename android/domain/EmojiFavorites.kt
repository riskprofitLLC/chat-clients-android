package chat.client.domain

import org.json.JSONArray
import org.json.JSONException

// Избранные эмодзи панели (аналог Favorites у Telegram): долгий тап по
// эмодзи закрепляет его в отдельной вкладке. Чистый кодек списка —
// хранение в AppPrefs JSON-массивом, как у недавних.
object EmojiFavorites {

    const val CAP = 16

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
            if (e.isNotBlank() && e !in out) out += e
            if (out.size >= CAP) break
        }
        return out
    }

    fun encode(list: List<String>): String {
        val arr = JSONArray()
        list.take(CAP).forEach { arr.put(it) }
        return arr.toString()
    }

    // переключение: закрепление — наверх, повтор — снятие
    fun toggle(list: List<String>, emoji: String): List<String> =
        if (emoji.isBlank()) {
            list
        } else if (emoji in list) {
            list.filter { it != emoji }
        } else {
            (listOf(emoji) + list).take(CAP)
        }
}
