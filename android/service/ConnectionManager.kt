package chat.client.service

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import chat.client.data.AppContainer
import chat.client.data.SessionInfo
import chat.client.net.RelayException
import chat.client.net.WsClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Единый менеджер соединения на всё приложение: ОДИН WebSocket и ОДИН
// цикл дрейна на сессию — общий для UI, foreground-сервиса и фонового
// worker'а. Здесь же тикеры самоудаления/автоудаления, прогон outbox
// и реакция на смену сети. Уведомления постит Notifier.
//
// Дрейн — по триггеру через conflated-канал (A14): запросы, пришедшие
// во время активного дрейна, не теряются, следующий прогон подхватывает.
class ConnectionManager(
    private val app: Application,
    private val container: AppContainer,
) {

    sealed interface Event {
        // реально сохранённые в историю новые сообщения
        data class Incoming(
            val byChat: Map<String, Int>,
            val reactions: Set<String>,
            val edits: Set<String> = emptySet(),
            val pins: Set<String> = emptySet(),
            val deletes: Set<String> = emptySet(),
            val avatars: Set<String> = emptySet(), // смена аватара без записи (U1)
            val votes: Set<String> = emptySet(), // голоса в опросах без уведомления (W1)
            val bios: Set<String> = emptySet(), // смена «О себе» без записи (W2)
            // Фаза X1: чаты с беззвучным последним сообщением — тихое уведомление
            val silentChats: Map<String, Boolean> = emptyMap(),
            val saved: Int,
            val broken: Int,
        ) : Event
        data class Typing(val peer: String) : Event
        data class Delivered(val peer: String, val at: Long) : Event
        data class Read(val peer: String, val at: Long) : Event

        // локальные удаления тикерами
        data class Removed(val chatId: String) : Event

        // системное уведомление о привязке нового устройства аккаунта
        data class NewDevice(val deviceId: String, val fingerprint: String) : Event
        data object AuthFailed : Event
        data object OutboxFlushed : Event
    }

    // состояние соединения (A24): Connecting до первого успеха,
    // дальше Online/Offline без ложного «нет соединения» при старте
    enum class ConnState { Connecting, Online, Offline }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(ConnState.Connecting)
    val connected: StateFlow<ConnState> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events

    // приложение на переднем плане? выставляет ChatApplication
    @Volatile var appInForeground: Boolean = false

    private val notifier = Notifier(app, container)

    @Volatile private var session: SessionInfo? = null
    private var ws: WsClient? = null
    private var drainLoop: Job? = null
    private var destructLoop: Job? = null
    private var outboxLoop: Job? = null
    private var mediaLoop: Job? = null
    // пишется из WS-потока OkHttp, читается в UI/дрейне — нужна видимость
    @Volatile private var everConnected = false

    // триггер дрейна: conflated-канал, параллельные запросы сливаются
    private val drainRequests = Channel<Unit>(Channel.CONFLATED)

    // A6: очередь докачек медиа — новый дрейн НЕ отменяет качающееся
    // (прежде cancel рвал недокачанные файлы и автосейв в галерею)
    private val mediaRequests = Channel<Pair<String, chat.client.domain.MediaInfo>>(Channel.UNLIMITED)

    // бэкофф дрейна при обрыве: 3с→5с→10с→30с→60с (кап)
    private val backoffSteps = longArrayOf(3_000, 5_000, 10_000, 30_000, 60_000)
    // resetBackoff зовётся из WS-потока, nextBackoff — из drainLoop (IO)
    @Volatile private var backoffIndex = 0

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "ConnectionManager"
    }

    // запуск после входа; повторный вызов с той же сессией — no-op
    @Synchronized
    fun start(s: SessionInfo) {
        if (session === s) return
        stopInternal()
        session = s
        everConnected = false
        _connected.value = ConnState.Connecting

        ws = WsClient(
            serverUrl = chat.client.data.Session.server(),
            token = s.token,
            onSignal = { frame -> handleFrame(frame) },
            onState = { up ->
                _connected.value = when {
                    up -> { everConnected = true; resetBackoff(); ConnState.Online }
                    everConnected -> ConnState.Offline
                    else -> ConnState.Connecting
                }
                // при переподключении догоняем пропущенное
                if (up) launchDrain()
            },
            onAuthError = { _events.tryEmit(Event.AuthFailed) },
        )
        registerNetworkCallback()

        // рабочий цикл дрейна: триггер из канала или период (A14);
        // conflated-канал не теряет запросы, пришедшие во время прогона
        drainLoop = scope.launch {
            while (isActive) {
                val idle = if (_connected.value == ConnState.Online) 30_000 else nextBackoff()
                kotlinx.coroutines.withTimeoutOrNull(idle) { drainRequests.receive() }
                val sNow = session ?: continue
                try {
                    drainOnce(sNow)
                } catch (_: Exception) {
                }
            }
        }
        // тикер самоудаления: спим до ближайшего дедлайна (не дольше 30с),
        // раз в минуту — проход автоудаления по умолчанию. A10: новый
        // таймер будит цикл немедленно — опоздание до 30 с устранено
        destructLoop = scope.launch {
            var lastAutoSweep = container.clock.now()
            while (isActive) {
                val now = container.clock.now()
                val next = container.destruct.nextDeadline(now)
                val sleep = next?.let { (it - now).coerceIn(50, 30_000) } ?: 30_000
                withTimeoutOrNull(sleep) { container.destruct.wake.first() }
                sweepDestruct()
                if (container.clock.now() - lastAutoSweep >= 60_000) {
                    lastAutoSweep = container.clock.now()
                    sweepAutoDelete()
                    sweepLiveGeo()
                }
            }
        }
        // A6: постоянный воркер докачек — очередь разбирается последовательно,
        // новый дрейн не отменяет текущую загрузку
        mediaLoop = scope.launch {
            for ((chatId, info) in mediaRequests) {
                val sNow = session ?: break
                container.messageRepo.fetchMediaAuto(app, sNow, chatId, info)
            }
        }
        // прогон outbox: недоставленные уходят при живом соединении
        outboxLoop = scope.launch {
            while (isActive) {
                if (_connected.value == ConnState.Online && container.outbox.entries.value.isNotEmpty()) {
                    val sNow = session
                    if (sNow != null) {
                        try {
                            // инвалидируем только при реальном прогрессе (A2):
                            // пока очередь просто ждёт сети, полных пересчётов нет
                            val completed = container.outbox.flushAll(sNow)
                            if (completed > 0) {
                                _events.tryEmit(Event.OutboxFlushed)
                                container.invalidateChats(null)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    delay(15_000)
                } else {
                    delay(5_000)
                }
            }
        }

        // запасной фоновый дрейн, если процесс убит системой
        BackgroundSyncWorker.ensureScheduled(app)
        launchDrain()
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        drainLoop?.cancel()
        drainLoop = null
        destructLoop?.cancel()
        destructLoop = null
        outboxLoop?.cancel()
        outboxLoop = null
        mediaLoop?.cancel()
        mediaLoop = null
        ws?.close()
        ws = null
        unregisterNetworkCallback()
        _connected.value = ConnState.Offline
        session = null
        resetBackoff()
    }

    // ручной дрейн (UI после отправки, wake-кадр); без сессии — no-op
    fun launchDrain() {
        if (session == null) return
        drainRequests.trySendBlocking(Unit)
    }

    private suspend fun drainOnce(s: SessionInfo) {
        try {
            val report = container.messageRepo.drain(app, s)
            if (report.saved > 0 || report.transfers > 0) {
                _events.tryEmit(
                    Event.Incoming(report.byChat, report.reactions, report.edits, report.pins, report.deletes, report.avatars, report.votes, report.bios, report.silentChats, report.saved, report.broken),
                )
                notifier.notifyIncoming(s, report.byChat, report.mentions, appInForeground, report.silentChats)
                // AL9 (аналог «Reacted to your message» у Telegram): реакции
                // на мои сообщения — отдельная шторка, теми же гейтами
                if (report.reactionNotices.isNotEmpty()) {
                    notifier.notifyReactions(s, report.reactionNotices, appInForeground)
                }
                // звук в приложении: новые сообщения при открытом приложении;
                // мьютнутые чаты молчат, беззвучный режим — в Sounds;
                // Фаза X1: беззвучные сообщения не дёргают и его
                if (appInForeground && container.appPrefs.inAppSounds()) {
                    for (chatId in report.byChat.keys) {
                        container.sounds.playIncoming(
                            enabled = true,
                            chatMuted = container.chatPrefs.muted(chatId),
                            silent = report.silentChats[chatId] == true,
                        )
                    }
                }
                container.invalidateChats(report.byChat.keys + report.reactions + report.edits + report.pins + report.deletes + report.avatars + report.votes + report.bios)
            }
            if (report.transfers > 0) {
                // перенесённое считаем прочитанным (A17)
                container.chatRepo.markAllRead(s)
                container.groups.restoreFromHistory(s.store)
                container.invalidateChats(null)
            }
            for (d in report.newDevices) {
                _events.tryEmit(Event.NewDevice(d.deviceId, d.fingerprint))
                notifier.notifyNewDevice(d.deviceId)
            }
            // докачка транзитного медиа — после истории и уведомлений (A14);
            // Фаза X5: политика сети — всегда / Wi-Fi / никогда
            for (item in report.mediaToFetch) {
                // A6: докачки копятся в очередь; воркер ниже разбирает
                mediaRequests.trySend(item)
            }
        } catch (e: RelayException) {
            // токен отозван/устарел: сессия мертва, реконнект бессмыслен
            if (e.code == 401 || e.code == 403) {
                _events.tryEmit(Event.AuthFailed)
                stop()
            }
        } catch (_: Exception) {
        }
    }

    // разбор кадров сервера
    private fun handleFrame(frame: String) {
        when {
            frame.startsWith("typing:") -> {
                _events.tryEmit(Event.Typing(frame.removePrefix("typing:")))
            }

            frame.startsWith("delivered:") -> {
                val rest = frame.removePrefix("delivered:")
                val peer = rest.substringBefore(':', rest)
                if (peer.isNotEmpty()) {
                    val at = container.clock.now()
                    recordDelivered(peer, at)
                    _events.tryEmit(Event.Delivered(peer, at))
                }
            }

            frame.startsWith("read:") -> {
                val peer = frame.removePrefix("read:")
                if (peer.isNotEmpty()) {
                    val at = container.clock.now()
                    recordRead(peer, at)
                    _events.tryEmit(Event.Read(peer, at))
                }
            }

            else -> if (frame == "newmsg") launchDrain()
        }
    }

    // ✓✓ даже при закрытом диалоге (A16): время доставки пишем в репозиторий.
    // В группе время привязано к участнику (A9): ✓✓ появляется только у
    // сообщений, отправленных ДО момента, когда участник забрал очередь
    private fun recordDelivered(peer: String, at: Long) {
        val s = session ?: return
        scope.launch {
            try {
                container.chatPrefs.setDeliveredAt(peer, at)
                for (g in container.groups.all()) {
                    if (peer in g.members) container.chatPrefs.setGroupMemberDeliveredAt(g.id, peer, at)
                }
                container.invalidateChats(setOf(peer))
            } catch (e: Exception) {
                Log.w(TAG, "delivered $peer не записан", e)
            }
        }
    }

    // синие ✓✓: время прочтения пишем в репозиторий (только 1:1)
    private fun recordRead(peer: String, at: Long) {
        val s = session ?: return
        scope.launch {
            try {
                container.chatPrefs.setReadAt(peer, at)
                container.invalidateChats(setOf(peer))
            } catch (e: Exception) {
                Log.w(TAG, "read $peer не записан", e)
            }
        }
    }

    // свип самоудаления: просроченные таймеры → удаление записей.
    // Индекс ts→record строится один раз на чат (A19), медиа чистим.
    private fun sweepDestruct() {
        val s = session ?: return
        try {
            val now = container.clock.now()
            val due = container.destruct.due(now)
            if (due.isEmpty()) return
            val legacySince = container.appPrefs.legacyDecodeSince()
            val touched = HashSet<String>()
            val orphanMedia = HashSet<String>()
            for (chatId in due.map { it.substringBefore('|') }.distinct()) {
                val index = HashMap<Long, chat.crypto.MessageRecord>()
                for (record in s.store.history(chatId, Int.MAX_VALUE)) index[record.timestamp] = record
                for (key in due) {
                    if (key.substringBefore('|') != chatId) continue
                    val ts = key.substringAfter('|').toLongOrNull() ?: continue
                    container.destruct.remove(chatId, ts)
                    val record = index[ts] ?: continue
                    s.store.deleteRecord(chatId, ts, record.content)
                    parseKind(record, legacySince)?.info?.id?.let { orphanMedia += it }
                    touched += chatId
                }
            }
            for (id in orphanMedia) container.media.deleteIfUnused(s, id)
            for (chatId in touched) {
                _events.tryEmit(Event.Removed(chatId))
                container.invalidateChats(setOf(chatId))
            }
        } catch (e: Exception) {
            Log.e(TAG, "свип самоудаления", e)
        }
    }

    // чат-автоудаление по умолчанию: записи старше окна уходят локально
    private fun sweepAutoDelete() {
        val s = session ?: return
        try {
            val now = container.clock.now()
            val legacySince = container.appPrefs.legacyDecodeSince()
            for (chatId in s.store.chatIds()) {
                val window = container.chatPrefs.autoDeleteSec(chatId)
                if (window <= 0) continue
                val cutoff = now - window * 1000
                val history = s.store.history(chatId, Int.MAX_VALUE)
                val stale = history.filter { it.timestamp < cutoff }
                val orphanMedia = HashSet<String>()
                for (record in stale) {
                    parseKind(record, legacySince)?.info?.id?.let { orphanMedia += it }
                }
                // K3: один пакет на чат — одна расшифровка на строку,
                // одна атомарная перезапись (было N перезаписей файла)
                val removed = stale.isNotEmpty() &&
                    s.store.deleteRecords(chatId, stale) > 0
                for (id in orphanMedia) container.media.deleteIfUnused(s, id)
                if (removed) {
                    _events.tryEmit(Event.Removed(chatId))
                    container.invalidateChats(setOf(chatId))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "свип автоудаления", e)
        }
    }

    // Фаза AA7 (аналог shareLiveLocation): раз в минуту — свежая точка
    // по каждой активной трансляции; истёкшие снимаются. Отправка — через
    // outbox: офлайн точки копятся и догоняются при соединении
    private suspend fun sweepLiveGeo() {
        val s = session ?: return
        try {
            val now = container.clock.now()
            for (chatId in container.liveGeo.expireDue(now)) {
                container.invalidateChats(setOf(chatId))
            }
            for ((chatId, _) in container.liveGeo.active.value) {
                if (!chat.client.domain.LiveGeo.shouldSend(container.liveGeo.lastSent(chatId), now)) {
                    continue
                }
                val point = chat.client.media.GeoLocation.currentLocation(app) ?: continue
                // 3.3: гейт очереди может отклонить (канал) — точка не
                // считается отправленной, повтор на следующем такте
                if (container.outbox.add(
                        chatId,
                        chat.client.domain.GeoFormat.encode(point.lat, point.lng),
                        point.text,
                        login = s.login,
                    ) != null
                ) {
                    container.liveGeo.markSent(chatId, now)
                }
                _events.tryEmit(Event.OutboxFlushed)
                container.invalidateChats(setOf(chatId))
            }
        } catch (e: Exception) {
            Log.e(TAG, "свип живой геопозиции", e)
        }
    }

    // ─── сеть: реконнект при появлении интернета ───

    // Фаза AA5: смена прокси — переподключение на новых клиентах
    fun restart() {
        val s = session
        if (s != null) {
            stopInternal()
            start(s)
        }
    }
    // registerDefaultNetworkCallback (A15): системная «дефолтная» сеть одна,
    // прыжки Wi-Fi↔мобильная больше не рвут живой сокет

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // onAvailable срабатывает при САМОЙ регистрации (B7): рвём только
        // реально мёртвое соединение, не только что начатое Connecting
        val networkSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (networkSeen.getAndSet(true) && _connected.value != ConnState.Online) ws?.reconnectNow()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    _connected.value == ConnState.Offline
                ) {
                    ws?.reconnectNow()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    // ─── уведомления ───

    fun cancelNotification(chatId: String) {
        notifier.cancelNotification(chatId)
    }

    // уведомления после дрейна из фонового worker'а; каналы уже созданы
    // при старте приложения (C3)
    fun notifyAfterBackgroundDrain(
        s: SessionInfo,
        byChat: Map<String, Int>,
        mentions: Set<String> = emptySet(),
        silentChats: Map<String, Boolean> = emptyMap(), // Фаза X1
        reactionNotices: List<chat.client.domain.ReactionNotify.Notice> = emptyList(), // AL9
    ) {
        notifier.notifyIncoming(s, byChat, mentions, appInForeground, silentChats)
        if (reactionNotices.isNotEmpty()) {
            notifier.notifyReactions(s, reactionNotices, appInForeground)
        }
    }

    // ─── бэкофф цикла дрейна ───

    private fun nextBackoff(): Long {
        val delayMs = backoffSteps[backoffIndex]
        if (backoffIndex < backoffSteps.lastIndex) backoffIndex++
        return delayMs
    }

    private fun resetBackoff() {
        backoffIndex = 0
    }
}

// медиа-вид записи без исключений — для свипов
private fun parseKind(
    record: chat.crypto.MessageRecord,
    legacySince: Long,
): chat.client.domain.MessageCodec.Kind.Media? = try {
    (chat.client.data.Session.parseRecord(record, legacySince) as? chat.client.domain.MessageCodec.Parsed.Content)
        ?.kind as? chat.client.domain.MessageCodec.Kind.Media
} catch (_: Exception) {
    null
}
