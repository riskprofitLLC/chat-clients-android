package chat.client.domain

// Фаза GB: стейт-машина звонка (аналог стейта звонков Telegram) —
// чистая логика без WebRTC/JNI, тестируется на JVM.
// IDLE → OUTGOING (мы позвонили) / RINGING (нам звонят) → ACTIVE → ENDED
// Угон: новый входящий при ACTIVE — отклоняем как busy.
object CallStateMachine {

    enum class Phase { IDLE, OUTGOING, RINGING, ACTIVE, ENDED }

    // кто инициатор текущего звонка
    enum class Side { OUTGOING, INCOMING }

    // почему звонок закончился — экран показывает точную причину
    // вместо безликого «звонок завершён»
    enum class EndReason { REMOTE, CANCELLED, BUSY, NETWORK, OTHER }

    class State(
        val phase: Phase = Phase.IDLE,
        val callId: String? = null,
        val peer: String? = null,
        val side: Side? = null,
        // момент перехода в ACTIVE — для таймера экрана
        val startedAt: Long = 0L,
        val endReason: EndReason? = null,
    ) {
        val busy: Boolean get() = phase == Phase.OUTGOING || phase == Phase.RINGING || phase == Phase.ACTIVE
    }

    fun idle(): State = State()

    // мы позвонили: offer ушёл
    fun outgoing(s: State, callId: String, peer: String, now: Long): State =
        if (s.busy || s.phase != Phase.IDLE) s
        else State(Phase.OUTGOING, callId, peer, Side.OUTGOING, now)

    // нам звонят
    fun incoming(s: State, callId: String, peer: String, now: Long): State =
        if (s.busy) s // занят: другой звонок — уже отвечает busy-событием
        else State(Phase.RINGING, callId, peer, Side.INCOMING, now)

    // ответили на наш звонок
    fun answerIncoming(s: State, callId: String): State =
        if (s.phase == Phase.OUTGOING && s.callId == callId) State(Phase.ACTIVE, callId, s.peer, s.side, s.startedAt)
        else s

    // наш вызов принят получателем
    fun acceptedOutgoing(s: State, callId: String, now: Long): State =
        if (s.phase == Phase.OUTGOING && s.callId == callId) State(Phase.ACTIVE, callId, s.peer, s.side, now)
        else s

    // мы приняли входящий
    fun acceptedIncoming(s: State, now: Long): State =
        if (s.phase == Phase.RINGING) State(Phase.ACTIVE, s.callId, s.peer, s.side, now) else s

    // звонок завершился (bye/cancel/busy/ошибка) — совпадение callId или сброс
    fun ended(s: State, callId: String?, reason: EndReason? = null): State =
        if (s.phase == Phase.IDLE) s
        else if (callId == null || s.callId == null || s.callId == callId)
            State(Phase.ENDED, s.callId, s.peer, s.side, s.startedAt, reason)
        else s

    // экран закрыт/сервис убит — вернуть в IDLE
    fun reset(s: State): State = State()
}
