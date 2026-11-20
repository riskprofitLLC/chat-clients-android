package chat.client.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

// Перенос истории между устройствами: чистая кодек-логика без контекста.
//
// Безопасность: transfer принимается ТОЛЬКО от своего логина (проверяет
// вызывающий по from кадра дрейна), каждый чанк валидируется по схеме.
// История режется на чанки — один блоб упирается в лимит сервера (10 МБ).
object TransferCodec {

    // один переносимый чат-запись
    class Item(val chat: String, val from: String, val ts: Long, val content: ByteArray)

    class Malformed(message: String) : Exception(message)

    const val MAX_RECORDS = 50_000 // защита от DoS-переноса
        const val MAX_RECORD_BYTES = 10L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
        // чанк: не более 200 записей и 6 МБ сырого контента
        const val CHUNK_RECORDS = 200
        const val CHUNK_BYTES = 6L * 1024 * 1024

        private val chatRe = Regex("^([a-z0-9_-]{3,32}|grp_[a-z0-9_-]{1,48})$")
        private val loginRe = Regex("^[a-z0-9_-]{3,32}$")

        // разрезает записи на чанки по числу и суммарному размеру
        fun chunk(items: List<Item>): List<List<Item>> {
            val out = ArrayList<List<Item>>()
            var current = ArrayList<Item>()
            var bytes = 0L
            for (item in items) {
                val size = item.content.size.toLong()
                if (current.isNotEmpty() &&
                    (current.size >= CHUNK_RECORDS || bytes + size > CHUNK_BYTES)
                ) {
                    out += current
                    current = ArrayList()
                    bytes = 0
                }
                current += item
                bytes += size
            }
            if (current.isNotEmpty()) out += current
            return out
        }

        fun encodeChunk(items: List<Item>): ByteArray {
            val arr = JSONArray()
            for (item in items) {
                arr.put(
                    JSONObject()
                        .put("chat", item.chat)
                        .put("from", item.from)
                        .put("ts", item.ts)
                        .put("content", Base64.getEncoder().encodeToString(item.content)),
                )
            }
            return arr.toString().encodeToByteArray()
        }

        // строгий разбор чанка; проверка источника кадра (from == мой логин)
        // осталась в вызывающем коде. Автор записи — не только я: входящие
        // сообщения принадлежат собеседнику (A3). Валидация автора:
        // 1:1 — это я или сам чат; группа — любой валидный логин (состав
        // уточняется при применении по конверту записи)
        fun decodeChunk(json: ByteArray, myLogin: String): List<Item> {
            val arr = try {
                JSONArray(json.decodeToString())
            } catch (_: Exception) {
                throw Malformed("перенос: битый JSON")
            }
            if (arr.length() > MAX_RECORDS) throw Malformed("перенос: слишком много записей")
            val out = ArrayList<Item>(arr.length())
            var total = 0L
            for (i in 0 until arr.length()) {
                val record = arr.getJSONObject(i)
                val chat = record.optString("chat")
                val from = record.optString("from")
                val ts = record.optLong("ts", -1)
                val contentB64 = record.optString("content")
                if (!chatRe.matches(chat)) throw Malformed("перенос: некорректный чат")
                if (!loginRe.matches(from)) throw Malformed("перенос: некорректный автор")
                if (!chat.startsWith("grp_") && from != myLogin && from != chat) {
                    throw Malformed("перенос: автор вне чата")
                }
                if (ts <= 0) throw Malformed("перенос: некорректное время")
                val content = try {
                    Base64.getDecoder().decode(contentB64)
                } catch (_: IllegalArgumentException) {
                    throw Malformed("перенос: битый content")
                }
                if (content.isEmpty() || content.size > MAX_RECORD_BYTES) {
                    throw Malformed("перенос: некорректный размер записи")
                }
                total += content.size
                if (total > MAX_TOTAL_BYTES) throw Malformed("перенос: превышен объём")
                out += Item(chat, from, ts, content)
            }
            return out
        }
}
