package chat.client.net

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// WebSocket-клиент wake-сигналов: сервер шлёт кадр "newmsg" при появлении
// сообщения в нашей очереди, "typing:<peer>", "delivered:<peer>:<device>",
// "read:<peer>". Отправлять в сокет нечего, только слушаем.
//
// Надёжность: pingInterval 25с ловит полуоткрытые соединения (NAT-таймауты).
// Реконнект планируется в однопоточном планировщике со счётчиком поколений:
// подключается только последнее поколение, параллельных сокетов не бывает.
// 3.9: набор отменённых сокетов убран — перед cancel поле socket
// обнуляется, события чужих/отменённых сокетов отбрасываются проверкой
// !== socket и не влияют на состояние. 401/403 на апгрейде — onAuthError.
class WsClient(
    serverUrl: String,
    token: String,
    private val onSignal: (String) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onAuthError: () -> Unit = {},
) {
    // http→ws, https→wss; запрос собираем сразу, токен нужен только здесь
    private val request = Request.Builder()
        .url(
            serverUrl.trim().trimEnd('/')
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://") + "/v1/ws",
        )
        .header("Authorization", "Bearer $token")
        .build()

    // readTimeout=0: соединение живёт неограниченно; ping ловит смерть.
    // Клиент общий на процесс — shard'имdispatcher уже под WsClient.
    // общий клиент процесса — прокси применяется и к WS (Фаза AA5)
    private val client = chat.client.net.NetClients.ws()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ws-reconnect") }
    private val closed = AtomicBoolean(false)
    private val attempt = AtomicInteger(0)
    private val generation = AtomicInteger(0)
    @Volatile private var socket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get() || webSocket !== socket) {
                webSocket.close(1000, null)
                return
            }
            attempt.set(0) // успешное подключение сбрасывает бэкофф
            onState(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed.get() || webSocket !== socket) return
            onSignal(text ?: "")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 3.9: события не-текущего сокета (в т.ч. отменённого нами,
            // поле socket обнуляется ДО cancel) не меняют состояние
            if (closed.get() || webSocket !== socket) return
            // токен отозван/устарел: реконнект бессмыслен
            if (response?.code == 401 || response?.code == 403) {
                onState(false)
                onAuthError()
                return
            }
            onState(false)
            scheduleReconnect(backoffDelay())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed.get() || webSocket !== socket) return
            onState(false)
            scheduleReconnect(backoffDelay())
        }
    }

    init {
        connect()
    }

    companion object {
        // бэкофф переподключения: 1,2,5,10, потом максимум 30с
        private val backoffMs = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    }

    private fun backoffDelay(): Long =
        backoffMs[attempt.getAndIncrement().coerceAtMost(backoffMs.lastIndex)]

    private fun connect() {
        if (closed.get()) return
        // предыдущий сокет гасим ПОСЛЕ подмены поля: события старого
        // отбрасываются проверкой !== socket, набор отменённых не нужен (3.9)
        val prev = socket
        socket = client.newWebSocket(request, listener)
        prev?.cancel()
    }

    // планирует подключение; выполняется только задача последнего поколения
    private fun scheduleReconnect(delayMs: Long) {
        if (closed.get()) return
        val gen = generation.incrementAndGet()
        scheduler.schedule({
            if (!closed.get() && generation.get() == gen) connect()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    // немедленный реконнект (сеть вернулась или сменилась). Вызывающий
    // сам решает, нужен ли он: живой сокет не рвём без причины.
    fun reconnectNow() {
        if (closed.get()) return
        attempt.set(0)
        // 3.9: обнуляем ДО cancel — onFailure отменённого не планирует бэкофф
        socket?.let { cur ->
            socket = null
            cur.cancel()
        }
        scheduleReconnect(0)
    }

    // закрывает сокет и останавливает цикл переподключения
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.let { cur ->
            socket = null // 3.9: сначала обнуляем, потом отменяем
            cur.cancel()
        }
        onState(false)
        scheduler.shutdownNow()
    }
}
