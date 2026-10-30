package chat.client.domain

import org.json.JSONArray
import org.json.JSONObject

// GE1 (аналог ChatsWidget у Telegram): содержимое виджета «Чаты» на главном
// экране. Чистая логика без Android — тестируется на JVM.
object WidgetFeed {

    const val MAX_ITEMS = 6 // потолок строк в виджете стандартного размера

    class Input(
        val chatId: String,
        val title: String,
        val preview: String?,
        val unread: Int,
        val ts: Long?,
        val hidden: Boolean, // чаты за PIN с главного экрана не светим
    )

    class Item(
        val chatId: String,
        val title: String,
        val preview: String,
        val unread: Int,
        val ts: Long?,
    )

    // как в списке чатов: свежие сверху, чат без сообщений — в конец;
    // скрытые исключены, пустые id отброшены, потолок MAX_ITEMS
    fun build(inputs: List<Input>): List<Item> =
        inputs.asSequence()
            .filter { !it.hidden && it.chatId.isNotBlank() }
            .sortedWith(
                compareByDescending<Input> { it.ts ?: 0L }
                    .thenByDescending { it.chatId },
            )
            .take(MAX_ITEMS)
            .map {
                Item(
                    chatId = it.chatId,
                    title = it.title.ifBlank { it.chatId },
                    preview = it.preview?.trim().orEmpty(),
                    unread = it.unread.coerceAtLeast(0),
                    ts = it.ts,
                )
            }
            .toList()

    // снапшот для виджета хранится в SharedPreferences — JSON org.json
    fun toJson(items: List<Item>): String {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("id", it.chatId)
                    .put("t", it.title)
                    .put("p", it.preview)
                    .put("u", it.unread)
                    .put("ts", it.ts ?: JSONObject.NULL),
            )
        }
        return arr.toString()
    }

    // битый JSON не должен ронять виджет — вернуть пустое
    fun fromJson(json: String): List<Item> = try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Item(
                chatId = o.optString("id"),
                title = o.optString("t"),
                preview = o.optString("p"),
                unread = o.optInt("u", 0).coerceAtLeast(0),
                ts = if (o.isNull("ts")) null else o.optLong("ts", 0L),
            )
        }.filter { it.chatId.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}
