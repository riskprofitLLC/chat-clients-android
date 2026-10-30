package chat.client.domain

// Конверт служебных сообщений.
//
// v2: [0xC5 0xA5 '2'][тип-байт][payload] — служебные форматы отличимы
// от пользовательского текста.
// v3: [0xC5 0xA5 '3'][тип-байт][id 8Б][payload] — каждый новый message
// получает случайный id, поэтому ref (sha256 content) уникален даже для
// двух одинаковых «ок» (A2). Чтение v2 сохранено для старых записей.
//
// Обычный текст всегда уходит в TYPE_TEXT-конверте v3: так каждое
// сообщение имеет уникальный id и стабильную ссылку для реакций/ответов.
object Envelope {

    // магия + версия; первый байт не '{', чтобы v1-клиент не принял JSON
    const val B0 = 0xC5.toByte()
    const val B1 = 0xA5.toByte()
    const val VERSION = '3'
    const val LEGACY_VERSION = '2'
    private const val ID_SIZE = 8

    const val TYPE_MEDIA = 'm'
    const val TYPE_GROUP = 'g'
    const val TYPE_REPLY = 'r'
    const val TYPE_REACTION = 'x'
    const val TYPE_TEXT = 't'
    const val TYPE_DEVICE = 'd'
    const val TYPE_SYSTEM = 's' // локальные системные записи (не по сети)
    const val TYPE_EDIT = 'e' // событие правки (по сети, в журнал не пишется)
    const val TYPE_EDITED = 'y' // текст после правки (маркер «изм.»)
    const val TYPE_PIN = 'p' // событие закрепа (по сети, в журнал не пишется)
    const val TYPE_DELETE = 'D' // событие «удалить у всех» (в журнал не пишется)
    const val TYPE_SYNC = 'z' // копия своего сообщения на свои устройства (C4)
    const val TYPE_POLL = 'o' // опрос (Фаза W1): сообщение с вопросом и вариантами
    const val TYPE_VOTE = 'v' // голос в опросе (Фаза W1): событие поверх, как реакция
    const val TYPE_BIO = 'b' // «О себе» (Фаза W2): событие, в журнал не пишется
    const val TYPE_CONTACT = 'c' // карточка контакта (Фаза W3)
    const val TYPE_GEO = 'l' // геопозиция (Фаза W4)
    const val TYPE_DRAFT = 'w' // черновик на свои устройства (Фаза AA8)
    const val TYPE_CALL = 'k' // событие звонка (Фаза GA): в журнал не пишется
    const val TYPE_FOLDERS = 'F' // папки на свои устройства (Фаза AJ): в журнал не пишется

    fun encode(type: Char, json: String): ByteArray = encodeBytes(type, json.encodeToByteArray())

    // payload — произвольные байты; v3 добавляет случайный id.
    // Байты id только из печатного ASCII (0x21..0x7E): конверт остаётся
    // безопасным для UTF-8-встраивания в групповой JSON-конверт ("c")
    fun encodeBytes(type: Char, payload: ByteArray): ByteArray {
        val raw = chat.crypto.CryptoPrimitives.randomBytes(ID_SIZE)
        val id = ByteArray(ID_SIZE) { i ->
            (0x21 + (raw[i].toInt() and 0xFF) % 0x5E).toByte()
        }
        val out = ByteArray(payload.size + 4 + ID_SIZE)
        out[0] = B0
        out[1] = B1
        out[2] = VERSION.code.toByte()
        out[3] = type.code.toByte()
        id.copyInto(out, 4)
        payload.copyInto(out, 4 + ID_SIZE)
        return out
    }

    class Decoded(val type: Char, val payload: ByteArray, val id: ByteArray?)

    // null — префикса нет (старый v1-формат)
    fun decode(content: ByteArray): Decoded? {
        if (content.size < 5) return null
        if (content[0] != B0 || content[1] != B1) return null
        return when (content[2]) {
            VERSION.code.toByte() -> {
                if (content.size < 5 + ID_SIZE) return null
                val type = content[3].toInt().toChar()
                Decoded(
                    type,
                    content.copyOfRange(4 + ID_SIZE, content.size),
                    content.copyOfRange(4, 4 + ID_SIZE),
                )
            }
            LEGACY_VERSION.code.toByte() -> {
                val type = content[3].toInt().toChar()
                Decoded(type, content.copyOfRange(4, content.size), null)
            }
            else -> null
        }
    }

    fun isEnvelope(content: ByteArray): Boolean =
        content.size >= 4 && content[0] == B0 && content[1] == B1

    // нужен ли побег: байты текста выглядят как конверт или JSON
    fun needsEscape(text: String): Boolean {
        val b = text.encodeToByteArray()
        if (b.size >= 2 && b[0] == B0 && b[1] == B1) return true
        return b.isNotEmpty() && b[0] == '{'.code.toByte()
    }

    // побег текста: всегда конверт v3 — каждое сообщение уникально (A2)
    fun escapeText(text: String): ByteArray =
        encodeBytes(TYPE_TEXT, text.encodeToByteArray())
}
