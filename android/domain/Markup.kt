package chat.client.domain

// Инлайн-разметка в стиле Telegram. Маркеры живут в самом тексте: журнал,
// wire-формат и векторы НЕ меняются, разбор происходит при показе, старые
// клиенты видят исходный текст с маркерами.
//
// Поддержка: **жирный**, _курсив_, ~зачёркнутый~, __подчёркнутый__,
// `код`, ```блок кода``` (многострочный), ||спойлер||, @логин —
// упоминание. Код и блок кода разбираются первыми и глушат остальные
// маркеры внутри. Диапазоны URL (LinkPreview.ranges) защищены:
// подчёркивания и тильды в адресах не превращаются в разметку.
//
// Правила границ против ложных срабатываний:
//  - открывающий маркер: после него не пробел; для ~ и _ перед ним
//    не буква/цифра (snake_case и «3~5» остаются текстом);
//  - закрывающий маркер: перед ним не пробел, содержимое непусто;
//  - пары жадные слева-направо, непарный маркер — обычный текст.
object Markup {

    enum class Style { BOLD, ITALIC, STRIKE, UNDERLINE, CODE, CODEBLOCK, SPOILER }

    // Кусок чистого текста (маркеры вырезаны) с накопленными стилями;
    // link — URL-адрес, mention — логин без «@», hashtag — тег без «#».
    class Segment(
        val text: String,
        val styles: Set<Style>,
        val link: String? = null,
        val mention: String? = null,
        val hashtag: String? = null,
    )

    // найденный спан: диапазон ВКЛЮЧАЕТ маркеры, контент — между ними
    private class Span(val style: Style, val start: Int, val end: Int, val tokenLen: Int) {
        val contentStart get() = start + tokenLen
        val contentEnd get() = end - tokenLen
    }

    // упоминание: диапазон @логина в исходном тексте
    private class Mention(val start: Int, val end: Int, val login: String)

    // Фаза AC2 (аналог хэштегов Telegram): #тег — буквы/цифры/_
    // (в т.ч. юникод), не длиннее 64, минимум один символ ≠ «_»;
    // диапазон ВКЛЮЧАЕТ «#» — в чистом тексте тег остаётся с ним
    private class Hashtag(val start: Int, val end: Int, val tag: String)

    private val hashtagRe = Regex("#([\\p{L}\\p{N}_]{1,64})")

    private class Marker(val style: Style, val token: String, val prevAlnumForbidden: Boolean)

    // порядок важен: блок кода ``` выделяется первым и глушит прочие
    // маркеры внутри; затем инлайн-код `; подчёркивание __ — до
    // курсива _ (общий символ: спаны __ блокируют одиночные _)
    private val fence = Marker(Style.CODEBLOCK, "```", prevAlnumForbidden = false)
    private val markers = listOf(
        Marker(Style.CODE, "`", prevAlnumForbidden = false),
        Marker(Style.SPOILER, "||", prevAlnumForbidden = false),
        Marker(Style.BOLD, "**", prevAlnumForbidden = false),
        Marker(Style.STRIKE, "~", prevAlnumForbidden = true),
        Marker(Style.UNDERLINE, "__", prevAlnumForbidden = true),
        Marker(Style.ITALIC, "_", prevAlnumForbidden = true),
    )

    private val mentionRe = Regex("@([a-z0-9_-]{3,32})")

    // 1.5: потолок длины для разметки — сверх него текст остаётся plain.
    // Легальный текст отправителя ≤4096, потолок с запасом; гонять спаны
    // по мегабайтному «отравленному» тексту нельзя
    const val MAX_PARSE = 16 * 1024

    private fun byStyle(style: Style): Marker = markers.first { it.style == style }

    // Фаза AD1 (аналог блоков кода у Telegram): ```…``` — многострочный
    // блок; содержимое между фенсами — как есть (переносы строк тоже),
    // разметка внутри не действует. Открывающий фенс — в любом месте,
    // содержимое непусто, закрывающий — следующий ``` вне URL.
    // Незакрытый — обычный текст.
    private fun findFencedBlocks(text: String, blocked: (Int) -> Boolean): List<Span> {
        val out = ArrayList<Span>()
        val n = text.length
        val len = fence.token.length
        // позиции-кандидаты на закрытие предсчитаны один раз (O(n)):
        // раньше каждый открывающий сканировал строку до конца — O(n²)
        // на незакрытых фенсах. Закрывающих больше нет — и пары не будет
        val closers = ArrayList<Int>()
        var p = text.indexOf(fence.token)
        while (p >= 0) {
            if (!blocked(p)) closers.add(p)
            p = text.indexOf(fence.token, p + 1)
        }
        if (closers.isEmpty()) return out
        var e = 0
        var i = 0
        while (i < n) {
            if (!text.startsWith(fence.token, i) || blocked(i)) {
                i++
                continue
            }
            while (e < closers.size && closers[e] < i + len) e++
            if (e >= closers.size) return out
            val close = closers[e]
            // содержимое начинается после открывающего фенса; пустое — не блок
            if (close == i + len) {
                i += len
                continue
            }
            out += Span(fence.style, i, close + len, len)
            i = close + len
            e++
        }
        return out
    }

    // Полный разбор: список сегментов чистого текста, покрывающий всё
    // сообщение (включая неразмеченные куски).
    // 1.5: сборка O(n) — маркеры и стили предрасчитаны масками, ссылки и
    // упоминания находятся бинарным поиском; прежний per-char any/filter
    // был O(n·k) и вешал превью и уведомления на текстах с тысячами спанов.
    // Текст длиннее MAX_PARSE не размечается вовсе — один plain-сегмент
    fun parse(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        if (text.length > MAX_PARSE) return listOf(Segment(text, emptySet()))
        val n = text.length

        // URL — защищённые зоны
        val urls = LinkPreview.ranges(text)
        val inUrl = BooleanArray(n)
        urls.forEach { u -> u.range.forEach { inUrl[it] = true } }

        // спаны разметки: блок кода ``` первым, затем инлайн-код —
        // оба глушат прочие маркеры внутри (Фаза AD1)
        val blockSpans = findFencedBlocks(text) { inUrl[it] }
        val inBlock = BooleanArray(n)
        blockSpans.forEach { s -> for (i in s.start until s.end) inBlock[i] = true }
        val codeSpans = findSpans(text, byStyle(Style.CODE)) { inUrl[it] || inBlock[it] }
        val inCode = BooleanArray(n)
        codeSpans.forEach { s -> for (i in s.start until s.end) inCode[i] = true }
        for (i in 0 until n) if (inBlock[i]) inCode[i] = true
        // спойлер/жирный — вне кода и URL
        val styleSpans = listOf(Style.SPOILER, Style.BOLD).flatMap { st ->
            findSpans(text, byStyle(st)) { inUrl[it] || inCode[it] }
        }
        // Фаза AD2: подчёркивание __ до курсива _, спаны __ блокируют
        // одиночные _ — иначе «__a__» читался бы курсивом
        val underlineSpans = findSpans(text, byStyle(Style.UNDERLINE)) { inUrl[it] || inCode[it] }
        val inUnderline = BooleanArray(n)
        underlineSpans.forEach { s -> for (i in s.start until s.end) inUnderline[i] = true }
        val strikeSpans = findSpans(text, byStyle(Style.STRIKE)) { inUrl[it] || inCode[it] }
        val italicSpans = findSpans(text, byStyle(Style.ITALIC)) {
            inUrl[it] || inCode[it] || inUnderline[it]
        }

        // упоминания вне URL: перед @ не буква/цифра (не e-mail)
        val mentions = ArrayList<Mention>()
        for (match in mentionRe.findAll(text)) {
            val s = match.range.first
            if (inUrl[s]) continue
            if (s > 0) {
                val prev = text[s - 1]
                if (prev.isLetterOrDigit() || prev == '@' || prev == '.') continue
            }
            var login = match.groupValues[1]
            // хвостовые -_ не входят в логин по правилам сервера
            while (login.length > 3 && !login.last().isLetterOrDigit()) login = login.dropLast(1)
            if (Validators.validLogin(login)) mentions += Mention(s, s + 1 + login.length, login)
        }

        // Фаза AC2: хэштеги вне URL и кода; '#' начинает слово — перед
        // ним не буква/цифра/_/# (иначе это не тег: snake_case, «C#» и т.п.)
        val hashtags = ArrayList<Hashtag>()
        for (match in hashtagRe.findAll(text)) {
            val s = match.range.first
            if (inUrl[s] || inCode[s]) continue
            if (s > 0) {
                val prev = text[s - 1]
                if (prev.isLetterOrDigit() || prev == '_' || prev == '#') continue
            }
            var tag = match.groupValues[1]
            // хвостовые подчёркивания не считаются частью тега
            while (tag.length > 1 && tag.last() == '_') tag = tag.dropLast(1)
            if (tag.any { it.isLetterOrDigit() }) {
                hashtags += Hashtag(s, s + 1 + tag.length, tag)
            }
        }

        // сборка чистого текста (1.5): маркеры/стили — маски, поиск — O(n)
        val allSpans = blockSpans + codeSpans + styleSpans + underlineSpans + strikeSpans + italicSpans
        val isMarkerMask = BooleanArray(n)
        val styleMask = IntArray(n)
        for (span in allSpans) {
            for (i in span.start until span.contentStart) isMarkerMask[i] = true
            for (i in span.contentEnd until span.end) isMarkerMask[i] = true
            for (i in span.contentStart until span.contentEnd) {
                styleMask[i] = styleMask[i] or (1 shl span.style.ordinal)
            }
        }

        // бинарный поиск диапазона, содержащего позицию; списки упорядочены
        // и не пересекаются (диапазоны из regex findAll)
        fun binaryStart(ranges: IntArray, pos: Int): Int {
            var lo = 0
            var hi = ranges.size - 1
            var res = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (ranges[mid] <= pos) {
                    res = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return res
        }

        val urlStarts = IntArray(urls.size) { urls[it].range.first }
        fun linkAt(pos: Int): String? {
            val idx = binaryStart(urlStarts, pos)
            if (idx < 0) return null
            val range = urls[idx].range
            if (pos > range.last || urls[idx].card == null) return null
            return urls[idx].url
        }

        val mentionStarts = IntArray(mentions.size) { mentions[it].start }
        fun mentionAt(pos: Int): String? {
            val idx = binaryStart(mentionStarts, pos)
            if (idx < 0) return null
            val m = mentions[idx]
            return if (pos < m.end) m.login else null
        }

        val hashtagStarts = IntArray(hashtags.size) { hashtags[it].start }
        fun hashtagAt(pos: Int): String? {
            val idx = binaryStart(hashtagStarts, pos)
            if (idx < 0) return null
            val h = hashtags[idx]
            return if (pos < h.end) h.tag else null
        }

        fun stylesOf(mask: Int): Set<Style> {
            if (mask == 0) return emptySet()
            val out = java.util.EnumSet.noneOf(Style::class.java)
            for (style in Style.entries) {
                if (mask and (1 shl style.ordinal) != 0) out.add(style)
            }
            return out
        }

        val out = ArrayList<Segment>()
        val sb = StringBuilder()
        var curMask = -1
        var curLink: String? = null
        var curMention: String? = null
        var curHashtag: String? = null
        var i = 0
        while (i < n) {
            if (isMarkerMask[i]) {
                i++
                continue
            }
            val mask = styleMask[i]
            val link = linkAt(i)
            val mention = mentionAt(i)
            val hashtag = hashtagAt(i)
            if (sb.isNotEmpty() && (mask != curMask || link != curLink ||
                    mention != curMention || hashtag != curHashtag)
            ) {
                out += Segment(sb.toString(), stylesOf(curMask), curLink, curMention, curHashtag)
                sb.setLength(0)
            }
            curMask = mask
            curLink = link
            curMention = mention
            curHashtag = hashtag
            sb.append(text[i])
            i++
        }
        if (sb.isNotEmpty()) {
            out += Segment(sb.toString(), stylesOf(curMask), curLink, curMention, curHashtag)
        }
        return out
    }

    // Текст без маркеров — для превью, цитат, уведомлений.
    fun strip(text: String): String {
        val b = StringBuilder()
        for (seg in parse(text)) b.append(seg.text)
        return b.toString()
    }

    // Жадный поиск пар маркера слева-направо с правилами границ.
    // O(n): позиции подходящих закрывающих предсчитаны один раз, поиск
    // пары — O(1) по указателю; раньше каждый открывающий сканировал
    // остаток строки — O(n²) на тексте из непарных маркеров (вешал UI)
    private fun findSpans(text: String, m: Marker, blocked: (Int) -> Boolean): List<Span> {
        val out = ArrayList<Span>()
        val n = text.length
        val len = m.token.length
        val closers = ArrayList<Int>()
        var p = text.indexOf(m.token)
        while (p >= 0) {
            if (!blocked(p) && p > 0 && !text[p - 1].isWhitespace()) closers.add(p)
            p = text.indexOf(m.token, p + 1)
        }
        if (closers.isEmpty()) return out
        var e = 0
        var i = 0
        while (i < n) {
            if (!text.startsWith(m.token, i) || blocked(i)) {
                i++
                continue
            }
            val after = i + len
            // открывающий: после него не пробел и не тот же маркер подряд
            if (after >= n || text[after].isWhitespace() || text.startsWith(m.token, after)) {
                i++
                continue
            }
            // для ~ и _ перед маркером не буква/цифра (snake_case и 3~5)
            if (m.prevAlnumForbidden && i > 0 && text[i - 1].isLetterOrDigit()) {
                i = after
                continue
            }
            // закрывающий — первый кандидат строго после открывающего;
            // кандидатов больше нет — дальше пар не будет вообще
            while (e < closers.size && closers[e] <= after) e++
            if (e >= closers.size) return out
            val close = closers[e]
            out += Span(m.style, i, close + len, len)
            i = close + len
            e++
        }
        return out
    }

    // Обёртка выделения маркером в поле ввода; повтор на уже обёрнутом
    // фрагменте снимает разметку (toggle). Возвращает новый текст и новое
    // выделение (start..end, start==end — пустой курсор между маркерами).
    fun applyMarker(text: String, selStart: Int, selEnd: Int, token: String): Pair<String, Pair<Int, Int>> {
        val start = minOf(selStart, selEnd).coerceIn(0, text.length)
        val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
        if (start == end) {
            // пустое выделение: вставляем пару, курсор между маркерами
            val t = text.substring(0, start) + token + token + text.substring(start)
            return t to (start + token.length to start + token.length)
        }
        val sel = text.substring(start, end)
        // toggle-off: маркеры целиком внутри выделения
        val wrappedInside = text.startsWith(token, start) &&
            text.regionMatches(end - token.length, token, 0, token.length) &&
            end - token.length >= start + token.length
        // toggle-off: маркеры вплотную снаружи выделения
        val wrappedOutside = start >= token.length &&
            text.regionMatches(start - token.length, token, 0, token.length) &&
            text.startsWith(token, end)
        if (wrappedInside) {
            val inner = text.substring(start + token.length, end - token.length)
            val t = text.substring(0, start) + inner + text.substring(end)
            return t to (start to start + inner.length)
        }
        if (wrappedOutside) {
            val t = text.substring(0, start - token.length) + sel + text.substring(end + token.length)
            return t to (start - token.length to end - token.length)
        }
        // обёртка: висячие пробелы остаются снаружи маркеров
        val lead = sel.length - sel.trimStart().length
        val trail = sel.length - sel.trimEnd().length
        val inner = sel.trim()
        if (inner.isEmpty()) return text to (start to end)
        val s = start + lead
        val t = text.substring(0, s) + token + inner + token + text.substring(start + sel.length - trail)
        return t to (s + token.length to s + token.length + inner.length)
    }
}
