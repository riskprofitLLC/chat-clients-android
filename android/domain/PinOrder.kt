package chat.client.domain

import org.json.JSONArray
import org.json.JSONException

// Ручной порядок закреплённых чатов (Фаза AG10, аналог reorder pinned
// chats у Telegram): список id закреплённых в порядке показа.
// Чистая логика — сдвиг на соседнюю позицию, границы — no-op.
object PinOrder {

    // сдвиг закрепа на delta позиций: -1 — выше, +1 — ниже.
    // Нет такого id, край списка или нулевой сдвиг — список без изменений
    fun move(list: List<String>, chatId: String, delta: Int): List<String> {
        // дубли схлопываем: порядок закрепов не должен ломаться
        val out = ArrayList<String>(list.size)
        for (id in list) if (id !in out) out += id
        val index = out.indexOfFirst { it == chatId }
        if (index < 0 || delta == 0) return list
        val target = index + delta
        if (target !in out.indices) return list
        out.removeAt(index)
        out.add(target, chatId)
        return out
    }

    // закрепление: id уходит в конец порядка; повтор — без изменений
    fun pin(list: List<String>, chatId: String): List<String> =
        if (chatId.isBlank() || chatId in list) list else list + chatId

    // открепление: id выпадает из порядка
    fun unpin(list: List<String>, chatId: String): List<String> =
        if (chatId in list) list.filter { it != chatId } else list

    // разбор сохранённого порядка: битый JSON и мусор — пустой список,
    // дубли отбрасываются
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
        }
        return out
    }

    fun encode(list: List<String>): String {
        val arr = JSONArray()
        for (id in list) if (id.isNotBlank()) arr.put(id)
        return arr.toString()
    }
}
