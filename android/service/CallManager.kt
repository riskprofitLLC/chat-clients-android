package chat.client.service

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import chat.client.data.AppContainer
import chat.client.data.CallRouter
import chat.client.data.MessageRepository
import chat.client.data.SessionInfo
import chat.client.domain.CallFormat
import chat.client.domain.CallRelay
import chat.client.domain.CallStateMachine
import chat.client.net.CallPipeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

// Фаза GB (аналог CallManager Telegram): ядро звонка — WebRTC-аудио 1:1.
// Стейт-машина — домен (CallStateMachine, JVM-тесты); здесь платформенное:
// фабрика пиров, AudioManager MODE_IN_COMMUNICATION, answer/ICE/bye через
// репозиторий. Криптокода нет: DTLS/SRTP внутри WebRTC; SDP едет нашим
// сигналингом (TYPE_CALL в sealed-блобе) — сервер не видит содержимое.
class CallManager(
    private val app: android.content.Context,
    private val container: AppContainer,
) : CallRouter {

    private val repo: MessageRepository get() = container.messageRepo
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notifier = chat.client.service.Notifier(app as android.app.Application, container)

    // диагностика живого звонка: путь сигналинга/ICE в logcat
    private fun log(msg: String) = android.util.Log.i("CallManager", msg)

    // GD: таймауты гудков — исходящий без ответа 45 с → BYE+«не ответили»;
    // входящий без ответа 45 с → BYE, репозиторий запишет «пропущенный»
    private fun scheduleRingTimeout() {
        scope.launch {
            kotlinx.coroutines.delay(RING_TIMEOUT_MS)
            val s = stateRef.value
            if (s.phase == CallStateMachine.Phase.RINGING) {
                log("ring timeout — отмена")
                declineIncoming()
            } else if (s.phase == CallStateMachine.Phase.OUTGOING) {
                log("ring timeout — вешаю трубку")
                endCurrentInternal("не ответили")
                s.peer?.let { p ->
                    s.callId?.let { id ->
                        sessionOrNull()?.let { ses ->
                            repo.sendCallEvent(ses, p, CallFormat.Event(CallFormat.EV_BYE, id, System.currentTimeMillis()))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        // GD: гудки 45 с — до записи «пропущенный» (CallFormat.STALE_MS=120 с запасом)
        private const val RING_TIMEOUT_MS = 45_000L
        // A4: окно на восстановление ICE после DISCONNECTED (смена сети)
        private const val NETWORK_GRACE_MS = 9_000L
    }

    // ENDED → IDLE через паузу, чтобы UI показал финал и закрылся сам
    private fun scheduleReset() {
        scope.launch {
            kotlinx.coroutines.delay(2500)
            val s = stateRef.value
            if (s.phase == CallStateMachine.Phase.ENDED) {
                stateRef.value = CallStateMachine.reset(s)
            }
        }
    }

    private val stateRef = MutableStateFlow(CallStateMachine.idle())
    val phase: StateFlow<CallStateMachine.State> = stateRef.asStateFlow()

    // кнопки экрана: микрофон/спикер (отдельные потоки — UI только читает)
    private val micMutedRef = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = micMutedRef.asStateFlow()
    private val speakerOnRef = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = speakerOnRef.asStateFlow()

    private var pc: PeerConnection? = null
    private var factoryRef: PeerConnectionFactory? = null
    private var localTrack: AudioTrack? = null
    private var remoteTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeaker: Boolean = false
    private var pendingOfferSdp: String? = null
    // ICE-кандидаты, пришедшие до accept (pc ещё null) — применяем после setRemote
    private val pendingCands = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var remotePeer: String? = null
    @Volatile private var callIdRef: String? = null

    // GC: relay-фолбэк — WS-пайп через релей + локальный UDP-мост
    private var pipe: CallPipeClient? = null
    private var bridge: CallPipeBridge? = null
    @Volatile private var pipeUp = false
    @Volatile private var iceConnected = false
    private var callStartedAt = 0L

    // поднять пайп на обоих концах: звонящий создаёт сессию, оба слушают WS
    private fun startRelayPipe() {
        val ses = sessionOrNull() ?: return
        val id = callIdRef ?: return
        if (pipe != null) return
        callStartedAt = System.currentTimeMillis()
        scope.launch {
            if (stateRef.value.side == CallStateMachine.Side.OUTGOING) {
                ses.api.createCallSession(ses.token, id)
            }
            val b = CallPipeBridge { bytes -> pipe?.send(bytes) ?: false }
            if (!b.start()) {
                log("relay bridge: порт ${CallRelay.BRIDGE_PORT} занят — фолбэк выключен")
                return@launch
            }
            bridge = b
            val p = CallPipeClient(
                chat.client.data.Session.server(), ses.token, id,
                onFrame = { b.inject(it) },
                onState = { up -> pipeUp = up },
            )
            pipe = p
            p.connect()
            scheduleRelayFallback()
        }
    }

    // если за FALLBACK_MS ICE не сошёлся — шлём кандидата локального моста
    private fun scheduleRelayFallback() {
        scope.launch {
            kotlinx.coroutines.delay(CallRelay.FALLBACK_MS)
            val s = stateRef.value
            if (s.phase == CallStateMachine.Phase.IDLE || s.phase == CallStateMachine.Phase.ENDED) return@launch
            if (CallRelay.shouldFallback(iceConnected, System.currentTimeMillis() - callStartedAt)) {
                log("relay fallback: ICE не сошёлся — кандидат моста")
                if (!pipeUp) pipe?.connect() // сессия могла запоздать — пробуем ещё раз
                val peer = remotePeer
                val id = callIdRef
                if (peer != null && id != null) {
                    sessionOrNull()?.let {
                        repo.sendCallEvent(
                            it, peer,
                            CallFormat.Event(
                                CallFormat.EV_ICE, id, System.currentTimeMillis(),
                                cands = listOf(CallRelay.bridgeCandidate()),
                            ),
                        )
                    }
                }
            }
        }
    }

    // причина завершения — для снека «звонок завершён: причина»
    @Volatile var lastReason: String? = null
        private set

    private fun sessionOrNull(): SessionInfo? = container.currentSession()

    // ─── исходящий ───

    fun startOutgoing(peer: String) {
        val cur = stateRef.value
        if (cur.phase != CallStateMachine.Phase.IDLE) return
        val id = CallFormat.newCallId()
        val next = CallStateMachine.outgoing(cur, id, peer, System.currentTimeMillis())
        if (next.phase != CallStateMachine.Phase.OUTGOING) return
        stateRef.value = next
        remotePeer = peer
        callIdRef = id
        log("startOutgoing to=$peer callId=$id")
        scheduleRingTimeout()
        startRelayPipe() // GC: пайп поднимаем сразу, relay-кандидат — по таймеру
        ensureAudioMode()
        takeFocus()
        val pc0 = buildPeer(peer)
        if (pc0 == null) {
            endCurrentInternal("webrtc недоступен")
            return
        }
        pc = pc0
        pc0.createOffer(object : SdpObserverImpl() {
            override fun onCreateSuccess(desc: SessionDescription) {
                log("offer created, len=${desc.description.length}")
                pc0.setLocalDescription(SdpObserverImpl(), desc)
                scope.launch {
                    val ok = sessionOrNull()?.let {
                        repo.sendCallEvent(
                            it, peer,
                            CallFormat.Event(CallFormat.EV_OFFER, id, System.currentTimeMillis(), sdp = desc.description),
                        )
                    } ?: false
                    log("offer sent=$ok")
                    if (!ok) endCurrentInternal("нет связи")
                }
            }
            override fun onCreateFailure(error: String?) {
                log("offer failed: $error")
                endCurrentInternal("sdp: $error")
            }
        }, MediaConstraints())
    }

    // ─── входящий ───

    fun acceptIncoming() {
        val s = stateRef.value
        if (s.phase != CallStateMachine.Phase.RINGING) return
        val peer = s.peer ?: return
        val id = s.callId ?: return
        log("acceptIncoming callId=$id from=$peer")
        CallService.start(app) // FGS microphone: микрофон только с ответа
        stateRef.value = CallStateMachine.acceptedIncoming(s, System.currentTimeMillis())
        ensureAudioMode()
        takeFocus()
        remotePeer = peer
        val pc0 = buildPeer(peer)
        if (pc0 == null) {
            endCurrentInternal("webrtc недоступен")
            return
        }
        pc = pc0
        val offer = pendingOfferSdp
        if (offer == null) {
            log("accept: нет offer")
            endCurrentInternal("нет offer")
            return
        }
        pc0.setRemoteDescription(
            object : SdpObserverImpl() {
                override fun onSetSuccess() {
                    log("setRemote offer ok, apply ${pendingCands.size} buffered cands")
                    val cands = pendingCands.toList()
                    pendingCands.clear()
                    for (c in cands) pc0.addIceCandidate(IceCandidate("", 0, c))
                }
                override fun onSetFailure(error: String?) {
                    log("setRemote offer failed: $error")
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, offer),
        )
        pc0.createAnswer(object : SdpObserverImpl() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc0.setLocalDescription(SdpObserverImpl(), desc)
                log("answer created, len=${desc.description.length}")
                scope.launch {
                    val ok = sessionOrNull()?.let {
                        repo.sendCallEvent(
                            it, peer,
                            CallFormat.Event(CallFormat.EV_ANSWER, id, System.currentTimeMillis(), sdp = desc.description),
                        )
                    } ?: false
                    log("answer sent=$ok")
                    if (!ok) endCurrentInternal("нет связи")
                }
            }
            override fun onCreateFailure(error: String?) { endCurrentInternal("sdp: $error") }
        }, MediaConstraints())
    }

    fun declineIncoming() {
        val s = stateRef.value
        val id = s.callId ?: return
        val peer = s.peer ?: return
        endCurrentInternal(null)
        scope.launch {
            sessionOrNull()?.let {
                repo.sendCallEvent(it, peer, CallFormat.Event(CallFormat.EV_BUSY, id, System.currentTimeMillis()))
            }
        }
    }

    fun hangUp() {
        val s = stateRef.value
        val id = s.callId
        val peer = s.peer
        endCurrentInternal()
        if (id != null && peer != null) {
            scope.launch {
                sessionOrNull()?.let {
                    repo.sendCallEvent(it, peer, CallFormat.Event(CallFormat.EV_BYE, id, System.currentTimeMillis()))
                }
            }
        }
    }

    fun toggleMic() {
        val next = !micMutedRef.value
        micMutedRef.value = next
        localTrack?.setEnabled(!next)
    }

    fun toggleSpeaker() {
        val am = audioManager ?: return
        val next = !speakerOnRef.value
        am.isSpeakerphoneOn = next
        speakerOnRef.value = next
    }

    fun endCurrent() {
        endCurrentInternal()
    }

    // ─── CallRouter: события звонка из дрейна ───

    override fun onCallEvent(from: String, event: CallFormat.Event) {
        val ses = sessionOrNull() ?: return
        log("onCallEvent ev=${event.ev} callId=${event.callId} from=$from myPhase=${stateRef.value.phase}")
        // A4: события (кроме нового OFFER) — только от нашего собеседника и с
        // текущим callId. Иначе посторонний оборвал бы идущий звонок.
        if (chat.client.domain.CallEventGate.decide(
                event.ev, event.callId, from, callIdRef, remotePeer,
            ) == chat.client.domain.CallEventGate.Decision.IGNORE
        ) {
            log("event ignored (stale/foreign) ev=${event.ev} from=$from")
            return
        }
        when (event.ev) {
            CallFormat.EV_OFFER -> {
                if (stateRef.value.busy) {
                    // занят: авто-busy — звонящий увидит «абонент занят»
                    scope.launch {
                        repo.sendCallEvent(ses, from, CallFormat.Event(CallFormat.EV_BUSY, event.callId, System.currentTimeMillis()))
                    }
                    return
                }
                stateRef.value = CallStateMachine.incoming(stateRef.value, event.callId, from, System.currentTimeMillis())
                pendingOfferSdp = event.sdp
                remotePeer = from
                callIdRef = event.callId
                log("incoming offer, sdp=${event.sdp?.length ?: 0}")
                scheduleRingTimeout()
                startRelayPipe() // GC: принимающая сторона тоже поднимает пайп
                // ринг в шторке (полноэкранный интент), если приложение свёрнуто
                sessionOrNull()?.let { notifier.notifyIncomingCall(it, from) }
            }
            CallFormat.EV_ANSWER -> {
                val s = stateRef.value
                if (s.phase == CallStateMachine.Phase.OUTGOING && s.callId == event.callId) {
                    event.sdp?.let { sdp ->
                        log("setRemote answer len=${sdp.length}")
                        pc?.setRemoteDescription(
                            object : SdpObserverImpl() {
                                override fun onSetFailure(error: String?) {
                                    log("setRemote answer failed: $error")
                                }
                            },
                            SessionDescription(SessionDescription.Type.ANSWER, sdp),
                        )
                    }
                    // answer получен — звонок активен у звонящего (EV_ACCEPT не шлём:
                    // согласование фаз — через answer/ICE)
                    stateRef.value = CallStateMachine.acceptedOutgoing(s, event.callId, System.currentTimeMillis())
                } else {
                    log("answer ignored phase=${s.phase} id=${s.callId}")
                }
            }
            CallFormat.EV_ICE -> event.cands.firstOrNull()?.let {
                // A4: валидируем кандидата (loopback/link-local вне моста — отбой)
                val bridge = chat.client.domain.CallRelay.isBridgeCandidate(it)
                if (!chat.client.domain.CallEventGate.validCandidate(it, bridge)) {
                    log("ice candidate rejected")
                    return@let
                }
                val cur = pc
                if (cur != null && stateRef.value.phase != CallStateMachine.Phase.RINGING) {
                    cur.addIceCandidate(IceCandidate("", 0, it))
                } else {
                    // RINGING: pc ещё нет — кандидаты буферизуются до accept
                    if (!pendingCands.contains(it)) pendingCands.add(it)
                    log("ice buffered (no pc), total=${pendingCands.size}")
                }
            }
            CallFormat.EV_ACCEPT -> stateRef.value = CallStateMachine.acceptedOutgoing(stateRef.value, event.callId, System.currentTimeMillis())
            CallFormat.EV_BUSY -> endCurrentInternal("абонент занят")
            CallFormat.EV_CANCEL ->
                // входящий отменили — «пропущенный» пишет репозиторий (GA)
                if (stateRef.value.side == CallStateMachine.Side.INCOMING) endCurrentInternal("звонок отменён")
            CallFormat.EV_BYE -> endCurrentInternal("звонок завершён")
        }
    }

    // A4: grace-таймер после ICE DISCONNECTED (смена сети) — 9 с на возврат
    private var networkGraceJob: Job? = null

    private fun scheduleNetworkGrace() {
        cancelNetworkGrace()
        networkGraceJob = scope.launch {
            delay(NETWORK_GRACE_MS)
            if (stateRef.value.phase != CallStateMachine.Phase.ENDED && !iceConnected) {
                endCurrentInternal("сеть")
            }
        }
    }

    private fun cancelNetworkGrace() {
        networkGraceJob?.cancel()
        networkGraceJob = null
    }

    // ─── WebRTC ───

    private fun ensureFactory(): PeerConnectionFactory {
        factoryRef?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val adm = JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        factoryRef = f
        return f
    }

    private fun buildPeer(peer: String): PeerConnection? {
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val f = ensureFactory()
        val source = f.createAudioSource(MediaConstraints())
        val track = f.createAudioTrack("a0", source)
        localTrack = track
        val pc0 = f.createPeerConnection(rtcConfig(), PeerEvents(peer))
        if (pc0 == null) return null
        pc = pc0
        // UNIFIED_PLAN: наш микрофон наружу + приём чужого аудио
        pc0.addTrack(track, listOf("chat-audio"))
        pc0.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        return pc0
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }

    // события пира: ICE наружу, входящий трек — в спикер, сеть — завершение
    private inner class PeerEvents(private val peer: String) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val id = callIdRef ?: return
            val peer = remotePeer ?: return
            log("ice candidate: ${candidate.sdp.take(60)}")
            scope.launch {
                sessionOrNull()?.let {
                    repo.sendCallEvent(
                        it, peer,
                        CallFormat.Event(CallFormat.EV_ICE, id, System.currentTimeMillis(), cands = listOf(candidate.sdp)),
                    )
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            log("pc state: $newState")
            if (newState == PeerConnection.PeerConnectionState.FAILED) endCurrentInternal("сеть")
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) cancelNetworkGrace()
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            log("ice state: $newState")
            // GC: CONNECTED/COMPLETED гасят relay-фолбэк
            iceConnected = newState == PeerConnection.IceConnectionState.CONNECTED ||
                newState == PeerConnection.IceConnectionState.COMPLETED
            when (newState) {
                // A4: FAILED — сразу; DISCONNECTED — временное состояние при
                // смене сети, даём grace-таймер на восстановление
                PeerConnection.IceConnectionState.FAILED -> endCurrentInternal("сеть")
                PeerConnection.IceConnectionState.DISCONNECTED -> scheduleNetworkGrace()
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> cancelNetworkGrace()
                else -> {}
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? AudioTrack)?.let {
                remoteTrack = it
                it.setEnabled(true)
            }
        }

        override fun onAddStream(stream: MediaStream) {
            stream.audioTracks.firstOrNull()?.let { remoteTrack = it }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onRemoveStream(stream: MediaStream) { remoteTrack = null }
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    // локальный/удалённый SDP — базовый наблюдатель без действий
    private open class SdpObserverImpl : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    // ─── завершение ───

    private fun endCurrentInternal(reason: String? = null) {
        val s = stateRef.value
        if (s.phase == CallStateMachine.Phase.IDLE) return
        log("end: ${s.phase} reason=$reason")
        pc?.close()
        pc = null
        remoteTrack = null
        pendingCands.clear()
        bridge?.stop() // GC: пайп и мост гасим вместе со звонком
        bridge = null
        pipe?.close()
        pipe = null
        pipeUp = false
        iceConnected = false
        notifier.cancelIncomingCall()
        CallService.stop(app)
        restoreAudioMode()
        stateRef.value = CallStateMachine.ended(stateRef.value, s.callId)
        pendingOfferSdp = null
        remotePeer = null
        if (reason != null) lastReason = reason
        scheduleReset()
    }

    // ─── AudioManager (sonnet A8: mode сохраняем/восстанавливаем) ───

    // GD: audio focus на звонок — пауза медиа-плееров (прецедент Voice.kt)
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var focusHeld = false
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { }

    private fun takeFocus() {
        val am = audioManager ?: return
        if (focusHeld) return
        focusHeld = try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (!focusHeld) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        } catch (_: Exception) {
        }
        focusHeld = false
        focusRequest = null
    }

    private fun ensureAudioMode() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
            savedMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    private fun restoreAudioMode() {
        abandonFocus()
        val am = audioManager ?: return
        try {
            am.mode = savedMode
        } catch (_: Exception) {
        }
        audioManager = null
    }

    fun dispose() {
        scope.cancel()
        factoryRef?.dispose()
        factoryRef = null
    }
}
