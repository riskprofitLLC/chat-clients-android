package chat.client.domain

// Единый разбор содержимого записи истории в UI-модель.
//
// Порядок разбора:
// 1) конверт v2 — служебное по типу-байту; групповой конверт разбирает
//    внутренний контент рекурсивно (глубина 1, вложенные группы запрещены);
// 2) строгие форматы v1 — ТОЛЬКО для записей, сохранённых до перехода
//    на v2 (параметр allowLegacy); новые входящие не разбирают v1 вовсе,
//    набранный пользователем JSON не становится служебным;
// 3) всё остальное — обычный текст.
//
// Неизвестный/битый конверт — Kind.Unsupported, а не пустой пузырь.
object MessageCodec {

    // 1.5: потолок текста на приёме — гигантский блоб не гоняется через
    // разметку, превью и индексы; легальный текст отправителя ≤4096,
    // потолок с запасом. Хвост сверх потолка не декодируется вовсе
    const val MAX_TEXT = 16 * 1024

    // декод текста с потолком: байты режем ДО декодирования (4 байта на
    // символ UTF-8 хватает с запасом), невалидный хвост — заменяющий символ
    private fun ByteArray.decodeTextCapped(): String {
        val limit = minOf(size, MAX_TEXT * 4)
        val text = if (size <= limit) decodeToString() else decodeToString(0, limit)
        return if (text.length <= MAX_TEXT) text else text.take(MAX_TEXT)
    }

    sealed interface Kind {
        // edited — текст после правки, помечается «изм.»
        data class Text(val text: String, val edited: Boolean = false) : Kind

        data class Reply(
            val author: String,
            val quote: String,
            val targetRef: String?,
            val targetTs: Long?,
            val text: String,
            // Фаза V2: переслано от — логин исходного автора
            val fwd: String? = null,
            // Фаза X1: беззвучная отправка — уведомление без звука;
            // тихий текст едет этим конвертом без автора/цитаты
            val silent: Boolean = false,
        ) : Kind

        data class Media(val info: MediaInfo) : Kind

        // опрос (Фаза W1): вопрос и варианты, анонимность;
        // Фаза Z1 (аналог Quiz): верный вариант (-1 — обычный) и пояснение;
        // Фаза AA9 (аналог multiple_choice): несколько ответов
        data class Poll(
            val question: String,
            val options: List<String>,
            val anonymous: Boolean,
            val correctIndex: Int = -1,
            val explanation: String? = null,
            val multi: Boolean = false,
        ) : Kind

        // карточка контакта (Фаза W3): логин + имя
        data class Contact(val login: String, val name: String?) : Kind

        // геопозиция (Фаза W4): координаты точки
        data class Geo(val lat: Double, val lng: Double) : Kind

        // служебное сообщение, которое этот клиент не понимает
        data object Unsupported : Kind

        // системное уведомление (новое устройство аккаунта и т.п.)
        data class SystemInfo(
            val text: String,
            // изменение состава группы — структура, локализуется при показе (B4)
            val change: GroupChange? = null,
            // запись «звонок» (Фаза GA): пропущенный — структура, локализация при показе
            val call: CallFormat.Record? = null,
        ) : Kind
    }

    class Group(val info: GroupInfo, val inner: Kind)

    sealed interface Parsed {
        val group: Group? // группа, если сообщение — групповой конверт

        data class Content(val kind: Kind, override val group: Group?) : Parsed
        data class ReactionOnly(val reaction: Reaction, override val group: Group?) : Parsed

        // голос в опросе (Фаза W1): как реакция — фильтруется из ленты,
        // агрегируется по ref опроса, уведомлением не является
        data class VoteOnly(val vote: VoteFormat.Vote, override val group: Group?) : Parsed
    }

    class Reaction(val targetRef: String?, val targetTs: Long?, val emoji: String)

    // разбор записи; allowLegacy=false для всего, что пришло после v2
    fun parse(content: ByteArray, allowLegacy: Boolean = true): Parsed =
        parseEnvelope(content, allowLegacy, depth = 0)

    private fun parseEnvelope(content: ByteArray, allowLegacy: Boolean, depth: Int): Parsed {
        val envelope = Envelope.decode(content)
        if (envelope != null) {
            return when (envelope.type) {
                Envelope.TYPE_MEDIA -> MediaJson.parse(envelope.payload)?.let {
                    Parsed.Content(Kind.Media(it), null)
                } ?: unsupported()

                Envelope.TYPE_REPLY -> ReplyFormat.decodeV2(envelope.payload)?.let {
                    Parsed.Content(
                        Kind.Reply(it.author, it.quote, it.targetRef, it.targetTs, it.text, it.fwd, it.silent),
                        null,
                    )
                } ?: unsupported()

                Envelope.TYPE_GROUP -> GroupFormat.decodeV2(envelope.payload)?.let { decoded ->
                    parseInner(decoded, allowLegacy, depth)
                } ?: unsupported()

                Envelope.TYPE_REACTION -> ReactionFormat.decodeV2(envelope.payload)?.let { (target, emoji) ->
                    Parsed.ReactionOnly(Reaction(target.ref, target.ts, emoji), null)
                } ?: unsupported()

                // опрос (Фаза W1): обычная запись журнала с вопросом и вариантами
                Envelope.TYPE_POLL -> PollFormat.decode(envelope.payload)?.let {
                    Parsed.Content(
                        Kind.Poll(it.question, it.options, it.anonymous, it.correctIndex, it.explanation, it.multi),
                        null,
                    )
                } ?: unsupported()

                // голос в опросе (Фаза W1): событие поверх, в ленте — агрегация
                Envelope.TYPE_VOTE -> VoteFormat.decode(envelope.payload)?.let {
                    Parsed.VoteOnly(it, null)
                } ?: unsupported()

                // карточка контакта (Фаза W3)
                Envelope.TYPE_CONTACT -> ContactFormat.decode(envelope.payload)?.let {
                    Parsed.Content(Kind.Contact(it.login, it.name), null)
                } ?: unsupported()

                // геопозиция (Фаза W4)
                Envelope.TYPE_GEO -> GeoFormat.decode(envelope.payload)?.let {
                    Parsed.Content(Kind.Geo(it.lat, it.lng), null)
                } ?: unsupported()

                // событие «О себе» (Фаза W2): в журнал не пишется, применяется
                // на приёме; сюда попадает только у старых клиентов
                Envelope.TYPE_BIO -> Parsed.Content(Kind.Unsupported, null)

                // побегнутый текст: разворачиваем в обычный текст;
                // 1.5: потолок приёма — хвост сверх не декодируем
                Envelope.TYPE_TEXT -> Parsed.Content(Kind.Text(envelope.payload.decodeTextCapped()), null)

                // текст после правки: тот же текст + маркер «изм。」
                Envelope.TYPE_EDITED -> Parsed.Content(
                    Kind.Text(envelope.payload.decodeTextCapped(), edited = true),
                    null,
                )

                Envelope.TYPE_DEVICE -> DeviceFormat.decode(envelope.payload)?.let {
                    Parsed.Content(Kind.SystemInfo("device:${it.deviceId}"), null)
                } ?: unsupported()

                // событие звонка (Фаза GA): применяется в репозитории на приёме,
                // в журнал не пишется — сюда попадает только у старых клиентов
                Envelope.TYPE_CALL -> Parsed.Content(Kind.Unsupported, null)

                // синк папок (Фаза AJ): применяется в репозитории на приёме,
                // в журнал не пишется — сюда попадает только у старых клиентов
                Envelope.TYPE_FOLDERS -> Parsed.Content(Kind.Unsupported, null)

                // локальная системная запись (состав группы и т.п.): сначала
                // структурированное изменение (B4), запись «звонок» (GA),
                // иначе — простой текст
                Envelope.TYPE_SYSTEM -> SystemFormat.decodeChange(envelope.payload)?.let {
                    Parsed.Content(Kind.SystemInfo("", it), null)
                } ?: chat.client.domain.CallFormat.recordDecode(envelope.payload)?.let {
                    Parsed.Content(Kind.SystemInfo("", null, it), null)
                } ?: SystemFormat.decode(envelope.payload)?.let {
                    Parsed.Content(Kind.SystemInfo(it), null)
                } ?: unsupported()

                // событие правки не хранится в журнале; сюда оно попадает
                // только у старых клиентов — показываем как служебное
                Envelope.TYPE_EDIT -> Parsed.Content(Kind.Unsupported, null)

                // событие закрепа — то же самое: в журнале его не бывает
                Envelope.TYPE_PIN -> Parsed.Content(Kind.Unsupported, null)

                // событие «удалить у всех» — аналогично: применяется на
                // приёме и в журнал не пишется (сюда попадает только у
                // старых клиентов, покажем как служебное)
                Envelope.TYPE_DELETE -> Parsed.Content(Kind.Unsupported, null)

                else -> unsupported()
            }
        }
        // новые входящие без конверта — только текст; v1-декодеры отключены.
        // 1.5: потолок приёма — гигантский блоб не хранится в Kind.Text целиком
        if (!allowLegacy) return Parsed.Content(Kind.Text(content.decodeTextCapped()), null)
        // строгие форматы v1 (старые записи)
        ReactionFormat.decodeLegacy(content)?.let { (target, emoji) ->
            return Parsed.ReactionOnly(Reaction(target.ref, target.ts, emoji), null)
        }
        GroupFormat.decodeLegacy(content)?.let { return parseInner(it, allowLegacy, depth) }
        return Parsed.Content(parsePlain(content), null)
    }

    // групповой конверт: внутренний контент без вложенных групп.
    // Внутри может быть любой v2-конверт (ответ, реакция, медиа, текст).
    private fun parseInner(decoded: GroupFormat.Decoded, allowLegacy: Boolean, depth: Int): Parsed {
        if (depth >= 1) return Parsed.Content(Kind.Unsupported, Group(decoded.group, Kind.Unsupported))
        val inner = parseEnvelope(decoded.inner, allowLegacy, depth + 1)
        return when (inner) {
            is Parsed.Content -> Parsed.Content(inner.kind, Group(decoded.group, inner.kind))
            is Parsed.ReactionOnly -> Parsed.ReactionOnly(inner.reaction, Group(decoded.group, Kind.Unsupported))
            is Parsed.VoteOnly -> Parsed.VoteOnly(inner.vote, Group(decoded.group, Kind.Unsupported))
        }
    }

    // медиа / ответ v1 / текст (только для старых записей)
    private fun parsePlain(content: ByteArray): Kind {
        MediaJson.parse(content)?.let { return Kind.Media(it) }
        val text = content.decodeTextCapped()
        if (text.startsWith("{")) {
            try {
                val json = org.json.JSONObject(text)
                if (json.has("r") && json.has("t")) {
                    val quote = json.optString("r")
                    val body = json.optString("t")
                    if (quote.isNotEmpty() && body.isNotEmpty() && quote.length <= 200) {
                        return Kind.Reply("", quote, null, null, body)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return Kind.Text(text)
    }

    private fun unsupported(): Parsed = Parsed.Content(Kind.Unsupported, null)

    fun isReaction(content: ByteArray): Boolean = when (val p = parse(content)) {
        is Parsed.ReactionOnly -> true
        else -> false
    }

    // событие правки в произвольном содержимом (1:1): null — не правка
    fun editOf(content: ByteArray) = EditFormat.of(content)

    fun groupOf(content: ByteArray, allowLegacy: Boolean = true): Group? = when (val p = parseEnvelope(content, allowLegacy, 0)) {
        is Parsed.Content -> p.group
        // реакция в групповом конверте — ТОЖЕ группа (A3): раньше теряла
        // группу и записывалась в чат отправителя, не показываясь на цели;
        // голос в опросе — тот же случай (Фаза W1)
        is Parsed.ReactionOnly -> p.group
        is Parsed.VoteOnly -> p.group
    }

    // разворачивает групповой конверт до «сырого» внутреннего содержимого
    fun innerContent(content: ByteArray): ByteArray {
        val envelope = Envelope.decode(content)
        if (envelope?.type == Envelope.TYPE_GROUP) {
            GroupFormat.decodeV2(envelope.payload)?.let { return it.inner }
        }
        return content
    }


    fun formatVoiceDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    // ─── превью для списка чатов (B4): тип — для иконки, текст — рядом.
    // Эмодзи не используются: иконку рисует UI, строки локализованы там же
    enum class PreviewKind { Text, Voice, File, Photo, Reply, Sticker, Video, VideoNote, Gif, Poll, Contact, Geo, Call }

    class Preview(val kind: PreviewKind, val text: String)

    // A11: allowLegacy — из записи журнала (записи ДО миграции — легаси,
    // свежие от недоверенного отправителя легаси-путь не проходят)
    fun preview(content: ByteArray, allowLegacy: Boolean = true): Preview? =
        when (val p = parse(content, allowLegacy)) {
        is Parsed.ReactionOnly -> Preview(PreviewKind.Text, p.reaction.emoji)
        // голос в опросе — не содержательное сообщение, превью нет (W1)
        is Parsed.VoteOnly -> null
        is Parsed.Content -> when (val k = p.kind) {
            is Kind.Media -> when {
                k.info.sticker -> Preview(PreviewKind.Sticker, "")
                k.info.type == "voice" -> Preview(PreviewKind.Voice, formatVoiceDuration(k.info.duration))
                k.info.type == "file" && k.info.videoNote -> Preview(PreviewKind.VideoNote, "")
                // GIF (Фаза U2): анимация в списке — своим типом превью
                k.info.type == "file" && k.info.mime == "image/gif" ->
                    Preview(PreviewKind.Gif, k.info.name ?: "")
                k.info.type == "file" && k.info.mime?.startsWith("video/") == true ->
                    Preview(PreviewKind.Video, k.info.name ?: "")
                k.info.type == "file" -> Preview(PreviewKind.File, k.info.name ?: "")
                else -> Preview(PreviewKind.Photo, "")
            }
            // Фаза V2: пересланный текст (без цитаты) — превью как текст,
            // а не ответ; Фаза X1: тихий текст — то же самое
            is Kind.Reply -> if (k.author.isEmpty() && k.quote.isEmpty() && (k.fwd != null || k.silent)) {
                Preview(PreviewKind.Text, Markup.strip(k.text).take(64))
            } else {
                Preview(PreviewKind.Reply, Markup.strip(k.text).take(64))
            }
            is Kind.Text -> Preview(PreviewKind.Text, Markup.strip(k.text).take(64))
            // опрос (Фаза W1): превью — вопрос, иконка — «опрос»
            is Kind.Poll -> Preview(PreviewKind.Poll, k.question.take(64))
            // контакт (Фаза W3): превью — имя или логин
            is Kind.Contact -> Preview(PreviewKind.Contact, k.name?.take(64) ?: "@${k.login}")
            // геопозиция (Фаза W4): превью — координаты (точка как разделитель)
            is Kind.Geo -> Preview(PreviewKind.Geo, GeoFormat.Point(k.lat, k.lng).text)
            is Kind.SystemInfo -> k.call?.let { Preview(PreviewKind.Call, "") }
                ?: Preview(PreviewKind.Text, k.text)
            Kind.Unsupported -> null
        }
    }
}
