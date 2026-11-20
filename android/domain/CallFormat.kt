package chat.client.domain

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// События голосового звонка (Фаза GA). Сигналинг едет через транзитную
// очередь релея в обычных sealed-блобах: offer/answer/ice — обмен WebRTC,
// accept/busy/cancel/bye — терминальные состояния гонки устройств.
// Запись «звонок» в журнале — локальная TYPE_SYSTEM-структура (B4:
// структура в журнале, локализация — при показе).
object CallFormat {

    const val EV_OFFER = "offer"
    const val EV_ANSWER = "answer"
    const val EV_ICE = "ice"
    const val EV_ACCEPT = "accept"
    const val EV_BUSY = "busy"
    const val EV_CANCEL = "cancel"
    const val EV_BYE = "bye"

    // просроченный offer: дозвон давно закончился. Порог щедрый к
    // расхождению часов — точную «пропущенность» даёт cancel отправителя
    const val STALE_MS = 120_000L

    private val callIdRe = Regex("^[a-z0-9]{8,32}$")
    private const val MAX_SDP = 64 * 1024
    private const val MAX_CANDS = 64
    private const val MAX_CAND = 512

    private val events = setOf(EV_OFFER, EV_ANSWER, EV_ICE, EV_ACCEPT, EV_BUSY, EV_CANCEL, EV_BYE)

    fun newCallId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // сетевое событие звонка
    class Event(
        val ev: String,
        val callId: String,
        val ts: Long,
        val sdp: String? = null,
        val cands: List<String> = emptyList(),
    )

    fun encode(ev: String, callId: String, ts: Long, sdp: String? = null, cands: List<String> = emptyList()): ByteArray {
        val json = JSONObject().put("e", ev).put("id", callId).put("ts", ts)
        sdp?.let { json.put("s", it) }
        if (cands.isNotEmpty()) json.put("c", JSONArray(cands))
        return Envelope.encode(Envelope.TYPE_CALL, json.toString())
    }

    // событие из конверта; не-звонок или мусор — null
    fun of(content: ByteArray): Event? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_CALL) return null
        return try {
            val json = JSONObject(envelope.payload.decodeToString())
            val ev = json.optString("e")
            val id = json.optString("id")
            val ts = json.optLong("ts", 0)
            if (ev !in events || !callIdRe.matches(id) || ts <= 0L) return null
            val sdpRaw = json.optString("s")
            if (sdpRaw.length > MAX_SDP) return null
            val sdp = sdpRaw.takeIf { it.isNotEmpty() }
            val cands = ArrayList<String>()
            val arr = json.optJSONArray("c")
            if (arr != null) {
                if (arr.length() > MAX_CANDS) return null
                for (i in 0 until arr.length()) {
                    val cand = arr.optString(i)
                    if (cand.isEmpty() || cand.length > MAX_CAND) return null
                    cands += cand
                }
            }
            Event(ev, id, ts, sdp, cands)
        } catch (_: Exception) {
            null
        }
    }

    // ─── запись журнала «звонок»: {cl:{id,st}} внутри TYPE_SYSTEM ───

    const val STATUS_MISSED = "missed"

    class Record(val callId: String, val status: String)

    fun encodeRecord(callId: String, status: String): ByteArray =
        Envelope.encode(
            Envelope.TYPE_SYSTEM,
            JSONObject().put("cl", JSONObject().put("id", callId).put("st", status)).toString(),
        )

    // запись из содержимого журнала; не-запись — null
    fun recordOf(content: ByteArray): Record? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_SYSTEM) return null
        return recordDecode(envelope.payload)
    }

    // callId записи или null — быстрый скан хвоста истории
    fun recordCallId(content: ByteArray): String? = recordOf(content)?.callId

    fun recordDecode(payload: ByteArray): Record? {
        return try {
            val cl = JSONObject(payload.decodeToString()).optJSONObject("cl") ?: return null
            val id = cl.optString("id")
            val st = cl.optString("st")
            // GA знает только «пропущенный»; новые статусы добавит GB вместе с рендером
            if (!callIdRe.matches(id) || st != STATUS_MISSED) null else Record(id, st)
        } catch (_: Exception) {
            null
        }
    }
}

// Приговор входящему событию: Live — живой звонок (в CallRouter, Фаза GB),
// Missed — «пропущенный» (отложенная запись в конце дрейна), Mute —
// терминальный дубль от гонки устройств
sealed interface CallVerdict {
    data class Live(val event: CallFormat.Event) : CallVerdict
    data class Missed(val event: CallFormat.Event) : CallVerdict
    data object Mute : CallVerdict
}

// Гейт звонков на сессию. Терминальные события (accept/busy/cancel/bye)
// идемпотентны по (callId, ev): второе accept от родственного устройства
// той же гонки не проходит. События от себя (между своими устройствами)
// записей не дают — только синхронизация ринга.
class CallGate(private val once: DedupIndex = DedupIndex(ONCE_CAPACITY)) {

    fun decide(self: Boolean, event: CallFormat.Event, now: Long): CallVerdict {
        if (event.ev in TERMINAL && once.remember("${event.callId}:${event.ev}")) {
            return CallVerdict.Mute
        }
        if (self) return CallVerdict.Live(event)
        return when (event.ev) {
            CallFormat.EV_CANCEL -> CallVerdict.Missed(event)
            CallFormat.EV_OFFER ->
                if (now - event.ts > CallFormat.STALE_MS) CallVerdict.Missed(event) else CallVerdict.Live(event)
            else -> CallVerdict.Live(event)
        }
    }

    companion object {
        private val TERMINAL = setOf(
            CallFormat.EV_ACCEPT, CallFormat.EV_BUSY, CallFormat.EV_CANCEL, CallFormat.EV_BYE,
        )
        const val ONCE_CAPACITY = 256
    }
}
