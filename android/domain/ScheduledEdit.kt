package chat.client.domain

import org.json.JSONObject

// Фаза AB6: правка отложенных — какие записи Outbox можно править
// (только текст: конверт собран, вложения и служебные — нет) и разбор
// текстового конверта обратно в черновик.
object ScheduledEdit {

    // текстовая ли запись: payload-конверт есть, вложения и control — нет
    fun canEdit(payload: ByteArray?, staged: Boolean, control: String): Boolean =
        payload != null && !staged && control.isEmpty()

    // разбор текстового конверта отложенной записи в черновик;
    // null — конверт не текст/ответ (править нельзя)
    data class Draft(val text: String, val replyAuthor: String?, val replySnippet: String?, val replyRef: String?, val replyTargetTs: Long?, val silent: Boolean)

    fun draftOf(payload: ByteArray): Draft? {
        val envelope = Envelope.decode(payload) ?: return null
        return when (envelope.type) {
            Envelope.TYPE_TEXT -> Draft(envelope.payload.decodeToString(), null, null, null, null, false)
            Envelope.TYPE_REPLY -> ReplyFormat.decodeV2(envelope.payload)?.let {
                Draft(it.text, it.author, it.quote, it.targetRef, it.targetTs, it.silent)
            }
            else -> null
        }
    }

    // превью для записи — чистый текст без разметки, как при отправке
    fun previewOf(text: String): String = Markup.strip(text).take(64)

    // время нормально для отложенной записи: 0 и прошлое — сразу
    fun normalizeSendAt(atMs: Long, nowMs: Long): Long =
        if (atMs <= 0) 0L else atMs.coerceAtLeast(nowMs)
}
