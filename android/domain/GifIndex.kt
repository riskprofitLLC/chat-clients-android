package chat.client.domain

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Индекс избранных GIF (Фаза AF8, аналог Saved GIFs у Telegram):
// чистый кодек JSON-массива [{"i":id,"n":имя,"s":байты,"t":добавлен}];
// вставка в конец (свежие в хвосте), при потолке MAX_ITEMS старейшие
// (начало списка) вытесняются. Чистая логика — тесты на JVM.
object GifIndex {
    const val MAX_ITEMS = 20
    const val MAX_BYTES = 10L * 1024 * 1024

    // одна запись индекса: id, имя, размер файла в байтах, момент добавления
    class Item(val id: String, val name: String, val size: Long, val addedAt: Long)

    // разбор сохранённого индекса; битый JSON и мусор — пустой список
    fun decode(raw: String?): List<Item> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("i", "")
            if (id.isEmpty()) continue
            // размер обязателен: нет или отрицательный — запись пропускается
            val size = o.optLong("s", -1L)
            if (size < 0L) continue
            out += Item(
                id,
                o.optString("n", ""),
                size,
                o.optLong("t", 0L),
            )
        }
        return out
    }

    // кодирование индекса как есть: потолок держит upsert
    fun encode(items: List<Item>): String {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject()
                    .put("i", e.id)
                    .put("n", e.name)
                    .put("s", e.size)
                    .put("t", e.addedAt),
            )
        }
        return arr.toString()
    }

    // новая запись в конец; повторный id — замена на новом месте
    fun upsert(list: List<Item>, item: Item, max: Int = MAX_ITEMS): List<Item> =
        (list.filter { it.id != item.id } + item).takeLast(max)

    // удаление по id; нет такого — список без изменений
    fun remove(list: List<Item>, id: String): List<Item> = list.filter { it.id != id }
}
