package chat.client.domain

import org.json.JSONObject
import java.util.Base64

// Синхронизация своих сообщений на СВОИ другие устройства (C4):
// конверт TYPE_SYNC + {"c":chatId,"d":base64(контент)}. Приём — только от
// session.login, идемпотентно по dedup внутреннего контента. Контент —
// те же байты, что пишутся в локальный журнал (для группы — конверт).
object SyncFormat {

    // А1: дескриптор фото/видео несёт thumb до 128 К символов base64 —
    // прежние 10 КБ отбрасывали ЛЮБОЕ вложение как Broken. 256 КБ покрывает
    // самый большой легальный конверт (MediaJson), потолок в проверках на
    // отправке и приёме один и тот же
    const val MAX_CONTENT = 256 * 1024

    class Decoded(val chatId: String, val content: ByteArray)

    fun encode(chatId: String, content: ByteArray): ByteArray =
        Envelope.encode(
            Envelope.TYPE_SYNC,
            JSONObject()
                .put("c", chatId)
                .put("d", Base64.getEncoder().encodeToString(content))
                .toString(),
        )

    fun decode(payload: ByteArray): Decoded? = try {
        val json = JSONObject(payload.decodeToString())
        val chatId = json.optString("c")
        val data = try {
            Base64.getDecoder().decode(json.optString("d"))
        } catch (_: IllegalArgumentException) {
            null
        }
        if (chatId.isEmpty() || chatId.length > 64 || data == null ||
            data.isEmpty() || data.size > MAX_CONTENT
        ) {
            null
        } else {
            Decoded(chatId, data)
        }
    } catch (_: Exception) {
        null
    }
}
