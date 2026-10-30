package chat.client.domain

import org.json.JSONArray
import org.json.JSONException

// AK8 (аналог Favorite Stickers у Telegram): избранные стикеры —
// закрепление долгим тапом, строка первой в пикере. Кодек — как у
// EmojiFavorites: список id в AppPrefs JSON-массивом.
object StickerFavorites {

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
            val id = arr.optString(i, "")
            if (id.isNotBlank() && id !in out) out += id
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
    fun toggle(list: List<String>, id: String): List<String> =
        if (id.isBlank()) {
            list
        } else if (id in list) {
            list.filter { it != id }
        } else {
            (listOf(id) + list).take(CAP)
        }

    // избранные, которые реально есть в наборе (удалённые выпадают)
    fun resolve(list: List<String>, pack: Collection<String>): List<String> =
        list.filter { it in pack }
}
