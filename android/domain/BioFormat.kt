package chat.client.domain

import org.json.JSONObject

// «О себе» (Фаза W2, аналог bio/About у Telegram): короткая строка
// профиля. Событие TYPE_BIO — в журнал не пишется, применяется к
// локальному хранилищу (по образцу аватаров U1): владелец bio обязан
// быть отправителем конверта, чужой логин молча игнорируется.
object BioFormat {

    const val MAX_BIO = 70

    // sanitize: трим + потолок; пустое — нет bio
    fun sanitize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_BIO)
    }

    // GE6: бизнес-часы едут опциональным полем "bh" (сырой JSON дней) —
    // старые клиенты, читающие только "t", его игнорируют
    // GH6: день рождения — опциональное поле "bd" ("DD.MM[.YYYY]"), тот же принцип
    fun encode(bio: String, hoursJson: String? = null, birthday: String? = null): ByteArray {
        val o = JSONObject().put("t", bio.take(MAX_BIO))
        if (!hoursJson.isNullOrBlank()) o.put("bh", JSONObject(hoursJson))
        Birthday.sanitize(birthday ?: "")?.let { o.put("bd", it) }
        return Envelope.encode(Envelope.TYPE_BIO, o.toString())
    }

    class Decoded(val text: String, val hoursJson: String? = null, val birthday: String? = null) {
        val remove: Boolean get() = text.isEmpty() && hoursJson == null && birthday == null // пустое — снятие bio
    }

    // строгий разбор: null — мусор; пустой текст — легитимное снятие
    fun decode(payload: ByteArray): Decoded? = try {
        val o = JSONObject(payload.decodeToString())
        val text = o.optString("t")
        if (text.length > MAX_BIO) {
            null
        } else {
            Decoded(text, o.optJSONObject("bh")?.toString(), o.optString("bd").ifEmpty { null })
        }
    } catch (_: Exception) {
        null
    }

    // событие из произвольного содержимого: null — не bio
    fun of(content: ByteArray): Decoded? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_BIO) return null
        return decode(envelope.payload)
    }
}
