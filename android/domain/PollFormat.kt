package chat.client.domain

import org.json.JSONArray
import org.json.JSONObject

// Опрос (Фаза W1, аналог TYPE_POLL у Telegram): сообщение-карточка
// с вопросом и вариантами. Сам опрос живёт в журнале как обычная запись;
// голоса — события TYPE_VOTE поверх (по образцу реакций), в журнале —
// одна актуальная запись на автора.
object PollFormat {

    const val MAX_QUESTION = 256
    const val MAX_OPTION = 100
    const val MAX_OPTIONS = 10
    const val MIN_OPTIONS = 2
    const val MAX_EXPLANATION = 200 // пояснение квиза (Фаза Z1)

    class Poll(
        val question: String,
        val options: List<String>,
        val anonymous: Boolean, // анонимный опрос: имена голосовавших скрыты
        // Фаза Z1 (аналог Quiz): индекс верного варианта (-1 — обычный
        // опрос) и пояснение; по сети — опц. поля "k"/"x"
        val correctIndex: Int = -1,
        val explanation: String? = null,
        // Фаза AA9 (аналог multiple_choice у Telegram): несколько ответов;
        // по сети — опц. поле "m"
        val multi: Boolean = false,
    )

    fun encode(
        question: String,
        options: List<String>,
        anonymous: Boolean,
        correctIndex: Int = -1,
        explanation: String? = null,
        multi: Boolean = false,
    ): ByteArray {
        val json = JSONObject()
            .put("q", question.take(MAX_QUESTION))
            // опции клипим на входе: декодер строгий, мусор не примет
            .put("o", JSONArray(options.map { it.take(MAX_OPTION) }))
            .put("a", if (anonymous) 1 else 0)
        // поля квиза пишем только с осмысленным значением: старые
        // клиенты видят обычный опрос, лишнего мусора в блобе нет
        if (correctIndex >= 0) json.put("k", correctIndex)
        val hint = explanation?.trim().orEmpty()
        if (hint.isNotEmpty()) json.put("x", hint.take(MAX_EXPLANATION))
        // несколько ответов: квиз — всегда одиночный, двойные флаги запрещены
        if (multi && correctIndex < 0) json.put("m", 1)
        return Envelope.encode(Envelope.TYPE_POLL, json.toString())
    }

    // строгий разбор payload: null — не опрос (мусор молча отбрасывается)
    fun decode(payload: ByteArray): Poll? {
        return try {
            val json = JSONObject(payload.decodeToString())
            val question = json.optString("q")
            if (question.isEmpty() || question.length > MAX_QUESTION) return null
            val arr = json.optJSONArray("o") ?: return null
            if (arr.length() < MIN_OPTIONS || arr.length() > MAX_OPTIONS) return null
            val options = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val option = arr.optString(i)
                if (option.isEmpty() || option.length > MAX_OPTION) return null
                options += option
            }
            val explanation = json.optString("x").trim().take(MAX_EXPLANATION)
            val correct = json.optInt("k", -1)
                .let { if (it in options.indices) it else -1 }
            Poll(
                question, options, json.optInt("a", 1) == 1,
                correct, explanation.ifEmpty { null },
                // многоответность живёт только вне квиза (AA9)
                correct < 0 && json.optInt("m", 0) == 1,
            )
        } catch (_: Exception) {
            null
        }
    }

    // опрос из произвольного содержимого: null — не опрос
    fun of(content: ByteArray): Poll? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_POLL) return null
        return decode(envelope.payload)
    }
}

// Голос в опросе (Фаза W1): конверт TYPE_VOTE + {"i":ref опроса,
// "c":индекс}. Снятие — c = -1 (как пустой эмодзи у реакций A2);
// стоп опроса — {"i":ref,"st":1}, принимается только от автора опроса.
// Фаза AA9: у многоответного опроса снятие одного варианта — "r":1,
// событие адресовано паре (автор, вариант).
object VoteFormat {

    class Vote(val targetRef: String?, val choice: Int, val stop: Boolean, val remove: Boolean = false)

    fun encode(pollRef: String, choice: Int, remove: Boolean = false): ByteArray =
        Envelope.encode(
            Envelope.TYPE_VOTE,
            JSONObject()
                .put("i", pollRef)
                .put("c", choice)
                .let { if (remove) it.put("r", 1) else it }
                .toString(),
        )

    fun encodeStop(pollRef: String): ByteArray =
        Envelope.encode(
            Envelope.TYPE_VOTE,
            JSONObject()
                .put("i", pollRef)
                .put("st", 1)
                .toString(),
        )

    // строгий разбор: null — мусор
    fun decode(payload: ByteArray): Vote? {
        return try {
            val json = JSONObject(payload.decodeToString())
            val ref = json.optString("i", "").ifEmpty { null } ?: return null
            if (json.optInt("st", 0) == 1) Vote(ref, -1, stop = true)
            else {
                val choice = json.optInt("c", -2)
                // валидны: индекс варианта или -1 (снятие)
                if (choice < -1) null
                else Vote(ref, choice, stop = false, remove = json.optInt("r", 0) == 1)
            }
        } catch (_: Exception) {
            null
        }
    }

    // событие из произвольного содержимого: null — не голос
    fun of(content: ByteArray): Vote? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_VOTE) return null
        return decode(envelope.payload)
    }
}

// Чистая агрегация голосов (Фаза W1): последняя запись каждого автора
// на опрос, как у реакций (A2); стоп учитывается только от автора опроса.
// Логика без Android — тестируется на JVM.
object PollAggregate {

    class State(
        val votes: Map<String, Int>, // автор → индекс варианта (без снявших)
        val myChoice: Int?, // мой голос или null
        val closed: Boolean, // автор остановил опрос
    )

    // записи голосов (ts, автор, голос) + автор опроса + мой логин
    fun build(entries: List<Triple<Long, String, VoteFormat.Vote>>, pollAuthor: String, myLogin: String): State {
        val latest = LinkedHashMap<String, Pair<Long, VoteFormat.Vote>>()
        var closed = false
        for ((ts, author, vote) in entries) {
            val prev = latest[author]
            if (prev == null || ts >= prev.first) latest[author] = ts to vote
            if (vote.stop && author == pollAuthor) closed = true
        }
        val votes = LinkedHashMap<String, Int>()
        for ((author, entry) in latest) {
            if (!entry.second.stop && !entry.second.remove && entry.second.choice >= 0) {
                votes[author] = entry.second.choice
            }
        }
        return State(votes, votes[myLogin], closed)
    }

    // счётчики по вариантам: индекс → число голосов
    fun counts(votes: Map<String, Int>, options: Int): IntArray {
        val out = IntArray(options)
        for (choice in votes.values) {
            if (choice in 0 until options) out[choice]++
        }
        return out
    }

    // проценты по вариантам; без голосов — нули
    fun percents(votes: Map<String, Int>, options: Int): IntArray {
        val c = counts(votes, options)
        val total = votes.size
        return IntArray(options) { if (total == 0) 0 else (c[it] * 100 / total) }
    }

    // Фаза AA9: агрегация многоответного опроса — последняя запись
    // на пару (автор, вариант); стоп — как у одиночного
    class MultiState(
        val votes: Map<String, Set<Int>>, // автор → выбранные варианты
        val myChoices: Set<Int>,
        val closed: Boolean,
    )

    fun buildMulti(
        entries: List<Triple<Long, String, VoteFormat.Vote>>,
        pollAuthor: String,
        myLogin: String,
    ): MultiState {
        val latest = LinkedHashMap<String, Pair<Long, VoteFormat.Vote>>()
        var closed = false
        for ((ts, author, vote) in entries) {
            if (vote.stop) {
                if (author == pollAuthor) closed = true
                continue
            }
            val key = "$author|${vote.choice}"
            val prev = latest[key]
            if (prev == null || ts >= prev.first) latest[key] = ts to vote
        }
        val votes = LinkedHashMap<String, MutableSet<Int>>()
        for ((key, entry) in latest) {
            if (entry.second.remove) continue
            val author = key.substringBefore('|')
            votes.getOrPut(author) { LinkedHashSet() } += entry.second.choice
        }
        return MultiState(votes, votes[myLogin] ?: emptySet(), closed)
    }

    // счётчики вариантов: сколько голосующих выбрало каждый
    fun countsMulti(votes: Map<String, Set<Int>>, options: Int): IntArray {
        val out = IntArray(options)
        for (choices in votes.values) {
            for (choice in choices) if (choice in 0 until options) out[choice]++
        }
        return out
    }

    // проценты многоответного: доля голосующих, выбравших вариант
    // (сумма по вариантам может быть больше 100 — как у Telegram)
    fun percentsMulti(votes: Map<String, Set<Int>>, options: Int): IntArray {
        val c = countsMulti(votes, options)
        val total = votes.size
        return IntArray(options) { if (total == 0) 0 else (c[it] * 100 / total) }
    }
}
