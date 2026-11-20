package chat.client.domain

import org.json.JSONObject

// Карточка контакта (Фаза W3, аналог «Поделиться контактом» Telegram):
// сообщение с логином и опциональным именем; тап по карточке открывает
// чат с этим логином. Старые клиенты видят «не поддерживается» —
// тот же прецедент, что у TYPE_PIN.
object ContactFormat {

    const val MAX_NAME = 64

    class Contact(val login: String, val name: String?) {
        // отображаемое имя: имя карточки без логина-суффикса
        val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "@$login"
    }

    fun encode(login: String, name: String?): ByteArray =
        Envelope.encode(
            Envelope.TYPE_CONTACT,
            JSONObject()
                .put("u", login)
                .putOpt("n", name?.take(MAX_NAME) ?: JSONObject.NULL)
                .toString(),
        )

    // строгий разбор: null — мусор; логин обязан быть валидным
    fun decode(payload: ByteArray): Contact? {
        return try {
            val json = JSONObject(payload.decodeToString())
            val login = json.optString("u")
            if (!Validators.validLogin(login)) return null
            // явная проверка NULL: optString на JSONObject.NULL отдаёт «null»-строку
            val name = if (json.isNull("n")) {
                null
            } else {
                json.optString("n").takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
            }
            Contact(login, name)
        } catch (_: Exception) {
            null
        }
    }

    // контакт из произвольного содержимого: null — не контакт
    fun of(content: ByteArray): Contact? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_CONTACT) return null
        return decode(envelope.payload)
    }
}
