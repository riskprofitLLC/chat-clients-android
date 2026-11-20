package chat.client.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

// Дескриптор медиа из JSON-сообщения: id блоба на сервере, контент-ключ,
// размеры/миниатюра; file добавляет имя и mime, voice — длительность
// и амплитуды волны (необязательное поле wf).
class MediaInfo(
    val id: String,
    val key: ByteArray,
    val size: Long,
    val width: Int,
    val height: Int,
    val thumbB64: String,
    val name: String? = null,
    val mime: String? = null,
    val type: String = "media", // media | file | voice
    val duration: Int = 0, // voice: секунды; file: только видео (Фаза P2)
    val waveform: ByteArray? = null, // амплитуды 0..255, ~10 сэмплов/сек
    val spoiler: Boolean = false, // Фаза O2: фото скрыто спойлером
    val sticker: Boolean = false, // Фаза P3: стикер рендерится без пузыря
    val videoNote: Boolean = false, // Фаза S1: видео-кружок — круглый рендер без пузыря
    // Фаза U1: аватар; "grp" — аватар группы, иначе логин владельца.
    // null — обычное медиа. Ава-конверт в журнал не пишется
    val avatarOwner: String? = null,
    // Фаза U1: снятие аватара — конверт без блоба
    val avatarRemove: Boolean = false,
    // Фаза V2: переслано от — логин исходного автора; отображается
    // заголовком «Переслано от X», по сети — поле "fwd" дескриптора
    val forwardFrom: String? = null,
    // Фаза V1: идентификатор альбома — общ у серии фото одной пачки;
    // по сети — поле "al", старые клиенты его игнорируют
    val album: String? = null,
    // Фаза X1: беззвучная отправка — уведомление получателя без
    // звука и вибрации; поле "sl" дескриптора, старые клиенты игнорируют
    val silent: Boolean = false,
    // Фаза AB1: одноразовое медиа — просмотр один раз, пересылка и
    // сохранение запрещены; поле "vo" дескриптора, старые клиенты игнорируют
    val viewOnce: Boolean = false,
    // Фаза AE7 (аналог эмодзи-аватара группы у Telegram): аватар-эмодзи
    // без блоба; по сети — ava="e:<эмодзи>", старые клиенты значение не
    // узнают и показывают инициалы
    val avatarEmoji: String? = null,
    // GE4 (аналог Stories у Telegram): история — истечение срока в мс;
    // null — обычное медиа. По сети — поля "st" (exp) и "sc" (подпись),
    // старые клиенты их игнорируют и видят обычное фото
    val storyExp: Long? = null,
    val storyCaption: String? = null,
) {
    // 3.13: типизированная копия вместо позиционных аргументов — при
    // добавлении поля позиционный конструктор молча путал значения
    fun copy(forwardFrom: String? = this.forwardFrom): MediaInfo = MediaInfo(
        id, key, size, width, height, thumbB64, name, mime, type,
        duration, waveform, spoiler, sticker, videoNote, avatarOwner,
        avatarRemove, forwardFrom, album, silent, viewOnce, avatarEmoji,
        storyExp, storyCaption,
    )

    // GE4: та же картинка как история (срок + подпись)
    fun asStory(exp: Long, caption: String?): MediaInfo = MediaInfo(
        id, key, size, width, height, thumbB64, name, mime, type,
        duration, waveform, spoiler, sticker, videoNote, avatarOwner,
        avatarRemove, forwardFrom, album, silent, viewOnce, avatarEmoji,
        exp, caption?.take(200),
    )
}

// Строгая валидация JSON дескриптора медиа: неизвестный/кривой JSON — это
// обычный текст, а не медиа.
object MediaJson {

    private val idRe = Regex("^[0-9a-f]{64}$")
    // Фаза V1: альбом-идентификатор — короткая hex-строка
    private val albumRe = Regex("^[0-9a-f]{4,32}$")
    private const val MAX_BLOB = 10L * 1024 * 1024

    // сериализация дескриптора; поля — как в v1, для читаемости людьми
    fun encode(info: MediaInfo): String {
        val json = JSONObject()
            .put("type", info.type)
            .put("id", info.id)
            .put("key", Base64.getEncoder().encodeToString(info.key))
            .put("size", info.size)
        when (info.type) {
            "file" -> {
                json.put("name", info.name).put("mime", info.mime ?: "application/octet-stream")
                // видео-файл (Фаза P2): опциональные метаданные карточки —
                // кадр, размеры, длительность; старые клиенты поля игнорируют
                if (info.mime?.startsWith("video/") == true) {
                    if (info.width > 0) json.put("w", info.width)
                    if (info.height > 0) json.put("h", info.height)
                    if (info.thumbB64.isNotEmpty()) json.put("thumb", info.thumbB64)
                    if (info.duration > 0) json.put("dur", info.duration)
                }
                // видео-кружок (Фаза S1): опциональный флаг, старые клиенты
                // видят обычную видео-карточку
                if (info.videoNote) json.put("vn", 1)
            }
            "voice" -> {
                json.put("duration", info.duration)
                // форма волны: по байту амплитуды, base64
                info.waveform?.let { wf -> json.put("wf", Base64.getEncoder().encodeToString(wf)) }
            }
            else -> {
                json.put("w", info.width).put("h", info.height).put("thumb", info.thumbB64)
                // спойлер (Фаза O2): опциональное поле, старые клиенты его игнорируют
                if (info.spoiler) json.put("sp", 1)
                // стикер (Фаза P3): опциональное поле, старые клиенты видят фото
                if (info.sticker) json.put("stk", 1)
                // аватар (Фаза U1) и эмодзи-аватар (Фаза AE7): одно поле ava —
                // эмодзи перекрывает владельца (блоба у него нет)
                when {
                    info.avatarEmoji != null -> json.put("ava", "e:" + info.avatarEmoji)
                    info.avatarOwner != null -> json.put("ava", info.avatarOwner)
                }
                if (info.avatarRemove) json.put("del", 1)
                // Фаза V1: альбом серии фото — опциональное поле, старые
                // клиенты его игнорируют
                info.album?.let { json.put("al", it) }
            }
        }
        // Фаза V2: атрибуция пересылки — у любого типа вложения;
        // старые клиенты поле игнорируют
        info.forwardFrom?.let { json.put("fwd", it) }
        // Фаза X1: беззвучная отправка — у любого типа вложения;
        // старые клиенты поле игнорируют
        if (info.silent) json.put("sl", 1)
        // Фаза AB1/AG5: одноразовое — у любого типа вложения (у голосовых
        // старые клиенты флаг не читают и играют обычное голосовое)
        if (info.viewOnce) json.put("vo", 1)
        // GE4: история — срок истечения и подпись; опциональные поля
        info.storyExp?.let { json.put("st", it) }
        info.storyCaption?.takeIf { it.isNotBlank() }?.let { json.put("sc", it.take(200)) }
        return json.toString()
    }

    // дескриптор в конверте TYPE_MEDIA — единственный путь новых сообщений
    fun message(info: MediaInfo): ByteArray = Envelope.encode(Envelope.TYPE_MEDIA, MediaJson.encode(info))

    // строгий разбор: null, если это не валидный дескриптор (в т.ч. просто текст)
    fun parse(content: ByteArray): MediaInfo? = parseOrNull(content.decodeToString())

    fun parseOrNull(text: String): MediaInfo? {
        if (!text.startsWith("{")) return null
        return try {
            val json = JSONObject(text)
            val type = json.optString("type")
            if (type != "media" && type != "file" && type != "voice") return null
            // аватар (Фаза U1): "grp" или логин владельца; Фаза AE7 —
            // эмодзи-аватар "e:<эмодзи>"; ava допустим только у media-типа,
            // мусорное значение — не аватар
            val ava = json.optString("ava")
            var avatarOwner: String? = null
            var avatarEmoji: String? = null
            if (type == "media" && ava.isNotEmpty()) {
                if (ava.startsWith("e:")) {
                    // один кластер, иначе дескриптор не читаем вовсе
                    avatarEmoji = AvatarEmoji.sanitize(ava.removePrefix("e:")) ?: return null
                    avatarOwner = "grp"
                } else if (ava == "grp" || Validators.validLogin(ava)) {
                    avatarOwner = ava
                } else {
                    return null
                }
            }
            // снятие аватара: блоба нет, остальное не проверяем
            if (avatarOwner != null && json.optInt("del", 0) == 1) {
                return MediaInfo(
                    "", ByteArray(0), 0, 0, 0, "", null, null, "media",
                    0, null, false, false, false, avatarOwner, true,
                )
            }
            // Фаза AE7: эмодзи-аватар — блоба нет, короткий выход как у снятия
            if (avatarEmoji != null) {
                return MediaInfo(
                    id = "", key = ByteArray(0), size = 0, width = 0, height = 0,
                    thumbB64 = "", type = "media",
                    avatarOwner = "grp", avatarEmoji = avatarEmoji,
                )
            }
            // Фаза V2: переслано от — опционально, мусор тихо теряется
            val fwdRaw = json.optString("fwd")
            val forwardFrom = if (fwdRaw.isNotEmpty() && Validators.validLogin(fwdRaw)) fwdRaw else null
            // Фаза V1: альбом — hex-идентификатор серии, мусор — не альбом
            val alRaw = json.optString("al")
            val album = if (albumRe.matches(alRaw)) alRaw else null
            // Фаза X1: беззвучная отправка — опциональный флаг, мусор — выкл
            val silentFlag = json.optInt("sl", 0) == 1
            // Фаза AB1: одноразовое медиа — опциональный флаг, мусор — выкл
            val viewOnceFlag = json.optInt("vo", 0) == 1
            // GE4: история — опциональные срок истечения и подпись
            val storyExp = json.optLong("st", 0L).takeIf { it > 0 }
            val storyCaption = json.optString("sc").takeIf { it.isNotBlank() }?.take(200)
            val id = json.optString("id")
            if (!idRe.matches(id)) return null
            val keyB64 = json.optString("key")
            val key = try {
                Base64.getDecoder().decode(keyB64)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (key.size != 32) return null
            val size = json.optLong("size", -1)
            if (size < 0 || size > MAX_BLOB) return null
            // w/h пишутся только у фото: голос и файл без них валидны
            val w = json.optInt("w", -1)
            val h = json.optInt("h", -1)
            if (type == "media" && (w < 0 || h < 0 || w > 100_000 || h > 100_000)) return null
            val thumbB64 = json.optString("thumb", "")
            if (thumbB64.length > 131_072) return null
            val wf = try {
                val s = json.optString("wf", "")
                if (s.isEmpty()) null else Base64.getDecoder().decode(s).also { if (it.isEmpty()) return null }
            } catch (_: IllegalArgumentException) {
                null
            }
            if (wf != null && wf.size > 8_640) return null // максимум ~14 минут по 10/с
            if (type == "file") {
                val name = json.optString("name", "")
                val mime = json.optString("mime", "application/octet-stream")
                if (name.isEmpty() || name.length > 256 || mime.length > 128) return null
                // длительность видео-файла (Фаза P2): опциональное "dur"
                val dur = json.optInt("dur", 0)
                if (dur < 0 || dur > 86_400) return null
                // видео-кружок (Фаза S1): мусорное значение — не кружок
                MediaInfo(
                    id, key, size, w, h, thumbB64, name, mime, "file", dur,
                    forwardFrom = forwardFrom, videoNote = json.optInt("vn", 0) == 1,
                    silent = silentFlag,
                    viewOnce = viewOnceFlag,
                    storyExp = storyExp, storyCaption = storyCaption, // GE4
                )
            } else if (type == "voice") {
                val duration = json.optInt("duration", 0)
                if (duration < 0 || duration > 86_400) return null
                MediaInfo(
                    id, key, size, w, h, thumbB64, null, null, "voice", duration, wf,
                    forwardFrom = forwardFrom, silent = silentFlag,
                    viewOnce = viewOnceFlag, // AG5: одноразовое голосовое
                    storyExp = storyExp, storyCaption = storyCaption, // GE4
                )
            } else {
                // спойлер читаем только у фото (Фаза O2); мусорное значение — выкл
                MediaInfo(
                    id, key, size, w, h, thumbB64, null, null, "media", 0, null,
                    json.optInt("sp", 0) == 1,
                    // стикер (Фаза P3): мусорное значение — не стикер
                    json.optInt("stk", 0) == 1,
                    false,
                    avatarOwner,
                    false,
                    forwardFrom,
                    album,
                    silentFlag,
                    viewOnce = viewOnceFlag,
                    storyExp = storyExp, storyCaption = storyCaption, // GE4
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

// Формат ответа (reply). v2: конверт + {"a":автор,"q":цитата,"i":ссылка,"s":ts,"t":текст}.
// Ссылка i = MessageRef.of(content) — стабильна у обеих сторон; поле s
// (ts цели) оставлено для чтения старых записей.
// Фаза V2: пересланный текст едет тем же конвертом с полем "fwd" —
// логин исходного автора (без a/q/i): старые клиенты видят текст без
// цитаты, новые — заголовок «Переслано от X».
object ReplyFormat {

    fun encode(
        author: String,
        quote: String,
        targetRef: String,
        targetTs: Long?,
        text: String,
        silent: Boolean = false, // Фаза X1: беззвучная отправка
    ): ByteArray =
        Envelope.encode(
            Envelope.TYPE_REPLY,
            JSONObject()
                .put("a", author)
                .put("q", quote.take(200))
                .put("i", targetRef)
                .put("s", targetTs ?: JSONObject.NULL)
                .put("t", text)
                .apply { if (silent) put("sl", 1) }
                .toString(),
        )

    // Фаза V2: конверт пересланного текста — автор обязателен
    fun encodeFwd(author: String, text: String): ByteArray =
        Envelope.encode(
            Envelope.TYPE_REPLY,
            JSONObject()
                .put("t", text)
                .put("fwd", author)
                .toString(),
        )

    // Фаза X1 (аналог silent post у Telegram): тихий текст — тот же
    // конверт TYPE_REPLY с полем "sl" и без автора/цитаты/цели; старые
    // клиенты видят обычный текст (пустая цитата деградирует в текст)
    fun encodeSilent(text: String): ByteArray =
        Envelope.encode(
            Envelope.TYPE_REPLY,
            JSONObject()
                .put("t", text)
                .put("sl", 1)
                .toString(),
        )

    class Decoded(
        val author: String,
        val quote: String,
        val targetRef: String?, // новые сообщения
        val targetTs: Long?, // старые записи
        val text: String,
        val fwd: String? = null, // Фаза V2: переслано от (логин автора)
        val silent: Boolean = false, // Фаза X1: беззвучная отправка
    )

    // конверт v2. 1.5: потолки на приёме — гигантские a/q/t с провода
    // не раздували память и индексы; цитата/автор усекаются, слишком
    // длинная ссылка приравнивается к отсутствию (всё равно не совпадёт)
    fun decodeV2(payload: ByteArray): Decoded? = try {
        val json = JSONObject(payload.decodeToString())
        val text = json.optString("t")
        if (text.isEmpty()) null
        else {
            // переслано от: только валидный логин, мусор — нет атрибуции
            val fwdRaw = json.optString("fwd")
            val fwd = if (fwdRaw.isNotEmpty() && Validators.validLogin(fwdRaw)) fwdRaw else null
            Decoded(
                author = json.optString("a", "").take(128),
                quote = json.optString("q", "").take(256),
                targetRef = json.optString("i", "").ifEmpty { null }?.takeIf { it.length <= 128 },
                targetTs = if (json.isNull("s")) null else json.optLong("s", -1).takeIf { it > 0 },
                text = text.take(MessageCodec.MAX_TEXT),
                fwd = fwd,
                silent = json.optInt("sl", 0) == 1,
            )
        }
    } catch (_: Exception) {
        null
    }
}

// Реакция: конверт TYPE_REACTION + {"i":ссылка,"to":ts,"e":эмодзи}.
// i — стабильная ссылка цели, to — ts цели (старые записи).
// Пустой эмодзи — снятие реакции автором (A2): последняя запись автора
// с "" убирает его реакцию с цели. Одна реакция на пользователя.
object ReactionFormat {

    // Набор реакций (Фаза M2: 28, было 6) — он же whitelist строгого
    // декодера: эмодзи вне набора с провода не принимаются.
    // Порядок — как в палитре выбора (Telegram-стиль)
    val EMOJIS = listOf(
        "👍", "❤️", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "😢", "😂",
        "🎉", "🤩", "🤮", "🙏", "👀", "💯", "😮", "🥲", "😇", "😍", "😎", "🤝",
        "⚡", "🌚", "🤡", "💔",
    )

    // двойной тап по пузырю — быстрый ❤ (Фаза M2); тогглом заведует VM
    const val QUICK_HEART = "❤️"

    // AH7 (аналог «показать все эмодзи» у Telegram): реакцией может быть
    // любой эмодзи-кластер — 1..16 code points из эмодзи-областей
    // (включая ZWJ-последовательности, тона кожи и флаги). Потолки —
    // против мусора с провода: длинные строки и текст не проходят
    fun isValidEmoji(emoji: String): Boolean {
        if (emoji.isEmpty() || emoji.length > 64) return false
        val cps = emoji.codePointCount(0, emoji.length)
        if (cps < 1 || cps > 16) return false
        var i = 0
        var sawEmoji = false
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            i += Character.charCount(cp)
            val isCore = cp in 0x1F000..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x2B00..0x2BFF ||
                cp in 0x2190..0x21FF ||
                cp in 0x2300..0x23FF ||
                cp in 0x25A0..0x25FF ||
                cp in 0x2900..0x297F ||
                cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 ||
                cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
            // служебные код-поинты (ZWJ, селекторы, ключкап) валидны
            // только ПРИСОВОКУПЛЁННЫМИ к эмодзи-ядру — сами по себе мусор
            val isModifier = cp == 0x200D || cp == 0x20E3 ||
                cp == 0xFE0E || cp == 0xFE0F
            if (!isCore && !isModifier) return false
            if (isCore) sawEmoji = true
        }
        return sawEmoji
    }

    // Фаза AB7: настройка реакции двойным тапом — белый список = набор
    // реакций; вне набора значение не сохраняется
    fun isQuickValid(emoji: String): Boolean = emoji in EMOJIS

    // Фаза AB7: значение настройки; мусор/пусто — дефолтный ❤
    fun quickOrDefault(emoji: String?): String =
        if (emoji != null && emoji in EMOJIS) emoji else QUICK_HEART

    class Target(val ref: String?, val ts: Long?)

    fun encode(target: Target, emoji: String): ByteArray =
        Envelope.encode(
            Envelope.TYPE_REACTION,
            JSONObject()
                .putOpt("i", target.ref ?: JSONObject.NULL)
                .put("to", target.ts ?: 0L)
                .put("e", emoji)
                .toString(),
        )

    // (ссылка/ts цели, эмодзи; "" — снятие) или null. AH7: принимается
    // любой валидный эмодзи-кластер, не только встроенный набор
    fun decodeV2(payload: ByteArray): Pair<Target, String>? = try {
        val json = JSONObject(payload.decodeToString())
        val emoji = json.optString("e")
        if (emoji.isNotEmpty() && !isValidEmoji(emoji)) null
        else {
            val ref = json.optString("i", "").ifEmpty { null }?.takeIf { it.length <= 128 }
            val ts = json.optLong("to", -1).takeIf { it > 0 }
            if (ref == null && ts == null) null
            else Target(ref, ts) to emoji
        }
    } catch (_: Exception) {
        null
    }

    // строгий v1: {"kind":"reaction","to":ts,"e":эмодзи} — только старые записи
    fun decodeLegacy(content: ByteArray): Pair<Target, String>? {
        return try {
            val text = content.decodeToString()
            if (!text.startsWith("{")) return null
            val json = JSONObject(text)
            if (json.optString("kind") != "reaction") return null
            val emoji = json.optString("e")
            val target = json.optLong("to", -1)
            if (target < 0 || emoji !in EMOJIS) null
            else Target(null, target) to emoji
        } catch (_: Exception) {
            null
        }
    }
}

// Системное уведомление о новом устройстве аккаунта (TYPE_DEVICE):
// {"id":device,"fp":отпечаток} — показывается уведомлением, не чатом.
object DeviceFormat {

    fun encode(deviceId: String, fingerprint: String): ByteArray =
        Envelope.encode(
            Envelope.TYPE_DEVICE,
            JSONObject().put("id", deviceId).put("fp", fingerprint).toString(),
        )

    class Decoded(val deviceId: String, val fingerprint: String)

    fun decode(payload: ByteArray): Decoded? = try {
        val json = JSONObject(payload.decodeToString())
        val id = json.optString("id")
        val fp = json.optString("fp")
        if (id.isEmpty() || id.length > 128 || fp.length > 128) null
        else Decoded(id, fp)
    } catch (_: Exception) {
        null
    }
}

// Локальная системная запись (TYPE_SYSTEM, только в журнал — не по сети):
// {"t":текст} — простая строка; {"mc":{...}} — структурированное изменение
// состава группы, локализуется при показе (B4)

// изменение состава: автор + добавленные/исключённые логины;
// renameTo — новое имя группы (Фаза N3); avatar — смена фото (U1);
// broadcast — режим канала (AB10): "" — выключен, логин — включён,
// null — к режиму отношения не имеет; descTo — новое описание группы
// (AC3, "" — снятие, null — не меняется); slowModeTo — период
// медленного режима (AC4, 0 — выключить, null — не меняется);
// noForwardTo — запрет пересылки/сохранения (AD6, null — не меняется)
class GroupChange(
    val sender: String,
    val added: List<String>,
    val removed: List<String>,
    val renameTo: String? = null,
    val avatar: Boolean = false,
    val broadcast: String? = null,
    val descTo: String? = null,
    val slowModeTo: Int? = null,
    val noForwardTo: Boolean? = null,
    // AI3: новый список администраторов (null — не менялся)
    val adminsTo: List<String>? = null,
    // Фаза AJ: запрет реакций (null — не меняется)
    val noReactionsTo: Boolean? = null,
)

object SystemFormat {

    fun encode(text: String): ByteArray =
        Envelope.encode(Envelope.TYPE_SYSTEM, JSONObject().put("t", text).toString())

    fun decode(payload: ByteArray): String? = try {
        val json = JSONObject(payload.decodeToString())
        if (json.has("mc")) null else {
            val text = json.optString("t")
            if (text.isEmpty() || text.length > 256) null else text
        }
    } catch (_: Exception) {
        null
    }

    // структурированное изменение состава (B4): без готового текста;
    // rn — опциональное новое имя при переименовании (Фаза N3);
    // ava — смена фото группы (Фаза U1); bc — режим канала (AB10);
    // gd — описание группы (AC3, "" — снятие); sm — медленный режим (AC4)
    fun encodeChange(change: GroupChange): ByteArray {
        val mc = JSONObject()
            .put("a", change.sender)
            .put("add", JSONArray(change.added))
            .put("rem", JSONArray(change.removed))
        change.renameTo?.let { mc.put("rn", it) }
        if (change.avatar) mc.put("ava", 1)
        // Фаза AB10: bc всегда при изменении режима; "" — выключение
        change.broadcast?.let { mc.put("bc", it) }
        // Фаза AC3: gd есть — описание меняется ("" — снятие)
        change.descTo?.let { mc.put("gd", it) }
        // Фаза AC4: sm есть — период меняется (0 — выключить)
        change.slowModeTo?.let { mc.put("sm", it) }
        // Фаза AD6: nf есть — запрет пересылки/сохранения меняется
        change.noForwardTo?.let { mc.put("nf", if (it) 1 else 0) }
        // Фаза AJ: nr есть — запрет реакций меняется
        change.noReactionsTo?.let { mc.put("nr", if (it) 1 else 0) }
        // AI3: ad есть — список администраторов меняется целиком
        change.adminsTo?.let { mc.put("ad", JSONArray(it)) }
        return Envelope.encode(
            Envelope.TYPE_SYSTEM,
            JSONObject().put("mc", mc).toString(),
        )
    }

    fun decodeChange(payload: ByteArray): GroupChange? {
        return try {
            val json = JSONObject(payload.decodeToString())
            val mc = json.optJSONObject("mc") ?: return null
            val sender = mc.optString("a")
            if (sender.isEmpty() || sender.length > 64) return null
            fun list(key: String): List<String> {
                val arr = mc.optJSONArray(key) ?: return emptyList()
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val login = arr.getString(i)
                    if (!Validators.validLogin(login)) throw IllegalArgumentException("bad login")
                    out += login
                }
                return out
            }
            val added = list("add")
            val removed = list("rem")
            // переименование (Фаза N3): допустимы пустые add/rem при наличии rn;
            // смена фото группы (Фаза U1) и режима канала (AB10) — тоже
            val rename = mc.optString("rn").takeIf { it.isNotEmpty() }
            if (rename != null && rename.length > 64) return null
            val avatar = mc.optInt("ava", 0) == 1
            // режим канала (AB10): bc есть — это изменение режима;
            // "" — выключение, валидный логин — включение
            val broadcast = if (mc.has("bc")) {
                val bc = mc.optString("bc")
                if (bc.isNotEmpty() && !Validators.validLogin(bc)) return null else bc
            } else {
                null
            }
            // описание группы (AC3): gd есть — новое описание, "" — снятие;
            // потолок 255, хвост молча обрезается — сообщение не роняем
            val descTo = if (mc.has("gd")) mc.optString("gd").take(255) else null
            // медленный режим (AC4): мусорный период — конверт не считаем
            // изменением режима; валидный 0 — выключение
            val slowModeTo = if (mc.has("sm")) {
                val sm = mc.optInt("sm", -1)
                if (!chat.client.domain.SlowMode.validPeriod(sm)) null else sm
            } else {
                null
            }
            // Фаза AD6: nf есть — запрет пересылки меняется; любой мусор
            // (строка, не-число) — «выключен», конверт не роняем
            val noForwardTo = if (mc.has("nf")) {
                mc.opt("nf").toString().toIntOrNull() == 1
            } else {
                null
            }
            // AI3: ad есть — новый список администраторов целиком;
            // пустой массив — легитимное «снять всех»; мусорные логины
            // отбрасываем по одному через runCatching, конверт не роняем
            val adminsPresent = mc.has("ad")
            val adminsTo = if (adminsPresent) {
                runCatching { list("ad") }.getOrNull() ?: return null
            } else {
                null
            }
            // Фаза AJ: nr есть — запрет реакций меняется; мусор — «выключен»
            val noReactionsTo = if (mc.has("nr")) {
                mc.opt("nr").toString().toIntOrNull() == 1
            } else {
                null
            }
            if (added.size > 50 || removed.size > 50) return null
            if (!avatar && rename == null && broadcast == null &&
                descTo == null && slowModeTo == null && noForwardTo == null &&
                noReactionsTo == null &&
                !adminsPresent && added.size + removed.size == 0
            ) return null
            GroupChange(sender, added, removed, rename, avatar, broadcast, descTo, slowModeTo, noForwardTo, adminsTo, noReactionsTo)
        } catch (_: Exception) {
            null
        }
    }
}

// Группа: имя + участники; определение раздаётся в самих сообщениях,
// сервер о группах не знает. v1: {"g":id,"n":имя,"m":[...],"c":контент}.
// v3.1: +"v" — ревизия состава/имени (A3): применяется только у конверта
// НОВЕЕ известного, конверты без v (=0) никогда не перетирают.
// AB10: +"bc" — режим канала: логин владельца (только он пишет).
// AC3: +"gd" — описание группы (≤255, пустое не пишем).
// AC4: +"sm" — медленный режим, секунд (0 не пишем).
// AD6: +"nf" — запрет пересылки/сохранения (1 — включён).
// AI3: +"ad" — администраторы (логины, могут удалять чужие сообщения)
class GroupInfo(
    val id: String,
    val name: String,
    val members: List<String>,
    val rev: Long = 0,
    val broadcast: String? = null,
    val desc: String = "",
    val slowMode: Int = 0,
    val noForward: Boolean = false,
    val admins: List<String> = emptyList(),
    // Фаза AJ (аналог Allowed Reactions у Telegram): реакции выключены
    val noReactions: Boolean = false,
)

// Правка сообщения: конверт TYPE_EDIT + {"i":ref цели,"to":ts цели,
// "c":base64(новый контент)}. Контент передаётся готовыми байтами — обе
// стороны хранят идентичные записи, ref совпадает для реакций/ответов
// (случайный id конверта генерируется отправителем один раз).
// Само событие в журнал не пишется — применяется к цели и отбрасывается。
object EditFormat {

    // prev — предыдущее содержимое цели (для истории правок, GH1);
    // опционально: старые клиенты поле не шлют и не читают
    class Decoded(val targetRef: String?, val targetTs: Long?, val newContent: ByteArray, val prev: ByteArray? = null)

    fun encode(targetRef: String, targetTs: Long, newContent: ByteArray, prev: ByteArray? = null): ByteArray {
        val o = JSONObject()
            .put("i", targetRef)
            .put("to", targetTs)
            .put("c", Base64.getEncoder().encodeToString(newContent))
        if (prev != null) o.put("pv", Base64.getEncoder().encodeToString(prev))
        return Envelope.encode(Envelope.TYPE_EDIT, o.toString())
    }

    fun decodeV2(payload: ByteArray): Decoded? = try {
        val json = JSONObject(payload.decodeToString())
        val ref = json.optString("i", "").ifEmpty { null }?.takeIf { it.length <= 128 }
        val ts = json.optLong("to", -1).takeIf { it > 0 }
        val content = try {
            Base64.getDecoder().decode(json.optString("c"))
        } catch (_: IllegalArgumentException) {
            null
        }
        val prev = try {
            json.optString("pv").ifEmpty { null }?.let {
                Base64.getDecoder().decode(it).takeIf { b -> b.size <= MAX_EDIT_CONTENT }
            }
        } catch (_: IllegalArgumentException) {
            null
        }
        if ((ref == null && ts == null) || content == null || content.isEmpty() ||
            content.size > MAX_EDIT_CONTENT) {
            null
        } else {
            Decoded(ref, ts, content, prev)
        }
    } catch (_: Exception) {
        null
    }

    // событие правки из произвольного содержимого: null — это не правка
    fun of(content: ByteArray): Decoded? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_EDIT) return null
        return decodeV2(envelope.payload)
    }

    // правка не больше 10 КБ: сам текст ограничен 4096, запас на конверт
    const val MAX_EDIT_CONTENT = 10 * 1024
}

// Закрепление сообщения: конверт TYPE_PIN + {"i":ref цели,"to":ts цели,
// "p":1|0}. Как событие правки — в журнал не пишется, применяется к
// локальному состоянию закрепа. Закрепить может любой участник чата.
object PinFormat {

    class Decoded(val targetRef: String?, val targetTs: Long?, val pinned: Boolean)

    fun encode(targetRef: String, targetTs: Long, pinned: Boolean): ByteArray =
        Envelope.encode(
            Envelope.TYPE_PIN,
            JSONObject()
                .put("i", targetRef)
                .put("to", targetTs)
                .put("p", pinned)
                .toString(),
        )

    fun decodeV2(payload: ByteArray): Decoded? = try {
        val json = JSONObject(payload.decodeToString())
        val ref = json.optString("i", "").ifEmpty { null }?.takeIf { it.length <= 128 }
        val ts = json.optLong("to", -1).takeIf { it > 0 }
        if (ref == null && ts == null) null
        else Decoded(ref, ts, json.optBoolean("p", true))
    } catch (_: Exception) {
        null
    }

    // событие закрепа из произвольного содержимого: null — не закреп
    fun of(content: ByteArray): Decoded? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_PIN) return null
        return decodeV2(envelope.payload)
    }
}

// «Удалить у всех» (Фаза P1, аналог revoke в Telegram): конверт
// TYPE_DELETE + {"i":ref цели,"to":ts цели}. Как правка/закреп — событие
// в журнал не пишется: получатель находит цель и убирает её у себя.
// Удалить может только автор своих сообщений (проверка в MessageDelete).
object DeleteFormat {

    class Decoded(val targetRef: String?, val targetTs: Long?)

    fun encode(targetRef: String, targetTs: Long): ByteArray =
        Envelope.encode(
            Envelope.TYPE_DELETE,
            JSONObject()
                .put("i", targetRef)
                .put("to", targetTs)
                .toString(),
        )

    fun decodeV2(payload: ByteArray): Decoded? = try {
        val json = JSONObject(payload.decodeToString())
        val ref = json.optString("i", "").ifEmpty { null }?.takeIf { it.length <= 128 }
        val ts = json.optLong("to", -1).takeIf { it > 0 }
        if (ref == null && ts == null) null else Decoded(ref, ts)
    } catch (_: Exception) {
        null
    }

    // событие удаления из произвольного содержимого: null — не удаление
    fun of(content: ByteArray): Decoded? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_DELETE) return null
        return decodeV2(envelope.payload)
    }
}

object GroupFormat {

    const val MAX_MEMBERS = 50

    private val groupIdRe = Regex("^grp_[a-z0-9_-]{1,48}$")

    fun encodeJson(
        id: String,
        name: String,
        members: List<String>,
        inner: ByteArray,
        rev: Long,
        broadcast: String? = null,
        desc: String = "",
        slowMode: Int = 0,
        noForward: Boolean = false,
        admins: List<String> = emptyList(),
        noReactions: Boolean = false,
    ): String {
        val json = JSONObject()
            .put("g", id)
            .put("n", name.take(64))
            .put("m", JSONArray(members))
            .put("v", rev)
            .put("c", inner.decodeToString())
        // Фаза AB10: режим канала едет в каждом конверте, как имя —
        // пропущенный change-конверт применится со следующим сообщением
        broadcast?.let { json.put("bc", it) }
        // Фаза AC3: описание едет в каждом конверте, как имя;
        // пустое не пишем — отсутствие поля и есть «нет описания»
        if (desc.isNotEmpty()) json.put("gd", desc.take(255))
        // Фаза AC4: медленный режим едет в каждом конверте (0 не пишем)
        if (slowMode > 0) json.put("sm", slowMode)
        // Фаза AD6: запрет пересылки/сохранения — в каждом конверте
        if (noForward) json.put("nf", 1)
        // AI3: администраторы — в каждом конверте, как остальные флаги;
        // пустой список не пишем — отсутствие поля и есть «админов нет»
        if (admins.isNotEmpty()) json.put("ad", JSONArray(admins))
        // Фаза AJ: запрет реакций — в каждом конверте
        if (noReactions) json.put("nr", 1)
        return json.toString()
    }

    fun encode(
        id: String,
        name: String,
        members: List<String>,
        inner: ByteArray,
        rev: Long = 0,
        broadcast: String? = null,
        desc: String = "",
        slowMode: Int = 0,
        noForward: Boolean = false,
        admins: List<String> = emptyList(),
        noReactions: Boolean = false,
    ): ByteArray = Envelope.encode(Envelope.TYPE_GROUP, encodeJson(id, name, members, inner, rev, broadcast, desc, slowMode, noForward, admins, noReactions))

    class Decoded(val group: GroupInfo, val inner: ByteArray)

    fun decodeV2(payload: ByteArray): Decoded? = decodeJson(payload.decodeToString())

    fun decodeJson(text: String): Decoded? = try {
        val json = JSONObject(text)
        val id = json.optString("g")
        val name = json.optString("n")
        val membersJson = json.optJSONArray("m")
        val inner = json.optString("c")
        // ревизия состава (A3): старые конверты без поля — 0, не перетирают
        val rev = json.optLong("v", 0L).coerceAtLeast(0L)
        // Фаза AB10: bc — режим канала; мусорный логин роняет конверт,
        // пустая строка — выключенный режим (нормализуем в null)
        val broadcast = if (json.has("bc")) {
            val bc = json.optString("bc")
            if (bc.isNotEmpty() && !Validators.validLogin(bc)) return null else bc.ifEmpty { null }
        } else {
            null
        }
        // Фаза AC3: описание — до 255, хвост молча режем, конверт не роняем
        val desc = if (json.has("gd")) json.optString("gd").take(255) else ""
        // Фаза AC4: медленный режим — мусорный период читаем как выключенный
        val slowMode = if (json.has("sm")) SlowMode.sanitize(json.optInt("sm", 0)) else 0
        // Фаза AD6: запрет пересылки/сохранения; мусор — «выключен»
        val noForward = if (json.has("nf")) json.optInt("nf", 0) == 1 else false
        // Фаза AJ: запрет реакций; мусор — «выключен»
        val noReactions = if (json.has("nr")) json.optInt("nr", 0) == 1 else false
        // AI3: администраторы — логины участников; мусорные отбрасываем,
        // конверт не роняем; поле всегда лишь у новых клиентов
        val adminsJson = json.optJSONArray("ad")
        val admins = ArrayList<String>()
        if (adminsJson != null) {
            for (i in 0 until adminsJson.length()) {
                val login = adminsJson.optString(i)
                if (login.isNotEmpty() && Validators.validLogin(login)) admins += login
            }
        }
        if (!groupIdRe.matches(id) || name.isEmpty() || name.length > 64 || membersJson == null || inner.isEmpty()) null
        else {
            val members = ArrayList<String>(membersJson.length())
            for (i in 0 until membersJson.length()) members += membersJson.getString(i)
            if (members.size !in 1..MAX_MEMBERS || members.any { !Validators.validLogin(it) }) null
            else Decoded(
                GroupInfo(id, name, members, rev, broadcast, desc, slowMode, noForward, admins.filter { it in members }, noReactions),
                inner.encodeToByteArray(),
            )
        }
    } catch (_: Exception) {
        null
    }

    // строгий v1: только полный корректный конверт группы (старые записи)
    fun decodeLegacy(content: ByteArray): Decoded? {
        val text = content.decodeToString()
        if (!text.startsWith("{")) return null
        return decodeJson(text)
    }
}
