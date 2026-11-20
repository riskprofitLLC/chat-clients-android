package chat.client.domain

// Пересылка сообщений: чистая логика подготовки payload без контейнера.
//
// Reply утекает цитату чужого чата — пересылаем только текст. Медиа из
// группового конверта НЕ пересылается конвертом как есть (A8): состав
// чужой группы утечёт в другой чат — берём внутренний контент и строим
// новый конверт TYPE_MEDIA с тем же дескриптором. Целевой чат сам
// обернёт payload в свой конверт (группа) или отправит как есть (1:1).
//
// Фаза V2 (аналог «Forwarded from» у Telegram): пересылка несёт атрибуцию
// исходного автора. Текст едет конвертом TYPE_REPLY с полем "fwd" (без
// цитаты), медиа — полем "fwd" дескриптора. Повторная пересылка сохраняет
// ИСХОДНОГО автора (цепочка не «затирает» его пересыльщиком).
// Фаза Y10 (аналог Forward Privacy): keepAuthor=false — пересылка без
// атрибуции: исходный автор не утекает, старые клиенты видят обычное
// сообщение (fwd не пишется вовсе — деградация без wire-изменений).
object MessageForward {

    fun payload(content: ByteArray, author: String? = null, keepAuthor: Boolean = true): ByteArray? {
        val inner = MessageCodec.innerContent(content)
        return when (val p = MessageCodec.parse(inner, allowLegacy = false)) {
            is MessageCodec.Parsed.Content -> when (val kind = p.kind) {
                is MessageCodec.Kind.Text -> fwdText(author.takeIf { keepAuthor }, kind.text)
                is MessageCodec.Kind.Reply ->
                    fwdText(kind.fwd.takeIf { keepAuthor } ?: author.takeIf { keepAuthor }, kind.text)
                is MessageCodec.Kind.Media -> MediaJson.message(
                    withFwd(kind.info, author.takeIf { keepAuthor }),
                )
                else -> null
            }
            else -> null
        }
    }

    // копия дескриптора с атрибуцией: исходный автор уже стоит — не трогаем;
    // keepAuthor=false — исходная атрибуция снимается (Фаза Y10).
    // 3.13: именованная копия вместо позиционного конструктора
    private fun withFwd(info: MediaInfo, author: String?): MediaInfo {
        if (author == null) {
            if (info.forwardFrom == null) return info
            return info.copy(forwardFrom = null)
        }
        if (info.forwardFrom != null) return info
        return info.copy(forwardFrom = author)
    }

    // текст пересылки: с автором — конверт fwd, без — прежний побег
    private fun fwdText(author: String?, text: String): ByteArray =
        if (author != null && Validators.validLogin(author)) {
            ReplyFormat.encodeFwd(author, text)
        } else {
            Envelope.escapeText(text)
        }
}
