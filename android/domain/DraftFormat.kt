package chat.client.domain

import org.json.JSONObject

// Синхронизация черновика между СВОИМИ устройствами (Фаза AA8, аналог
// draft-синка у Telegram): конверт TYPE_DRAFT {"c":chatId,"t":текст,
// "a":ms}. Уходит только на свои устройства; в журнал НЕ пишется —
// применяется к локальному черновику, если он новее.
object DraftFormat {

    const val MAX_TEXT = 4096

    class Decoded(val chatId: String, val text: String, val at: Long)

    fun encode(chatId: String, text: String, at: Long): ByteArray =
        Envelope.encode(
            Envelope.TYPE_DRAFT,
            JSONObject()
                .put("c", chatId)
                .put("t", text.take(MAX_TEXT))
                .put("a", at)
                .toString(),
        )

    // строгий разбор: null — не черновик (мусор молча отбрасывается)
    fun decode(payload: ByteArray): Decoded? = try {
        val json = JSONObject(payload.decodeToString())
        val chatId = json.optString("c")
        val at = json.optLong("a", 0L)
        if (chatId.isEmpty() || chatId.length > 64 || at <= 0L) null
        else Decoded(chatId, json.optString("t"), at)
    } catch (_: Exception) {
        null
    }

    fun of(content: ByteArray): Decoded? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_DRAFT) return null
        return decode(envelope.payload)
    }
}
