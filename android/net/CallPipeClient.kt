package chat.client.net

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

// Фаза GC: бинарный WS-пайп звонка — /v1/calls/{callId}/ws (Bearer).
// Кадры идут как есть (DTLS/SRTP-пакеты, opaque для сервера); текстовые
// кадры не шлём. Без реконнекта: пайп живёт ровно пока живёт звонок —
// разрыв одного конца рвёт другой на сервере, так что переподключение
// бессмысленно. В отличие от WsClient, здесь двусторонний обмен.
class CallPipeClient(
    serverUrl: String,
    token: String,
    callId: String,
    private val onFrame: (ByteArray) -> Unit,
    private val onState: (Boolean) -> Unit = {},
) {
    private val request = Request.Builder()
        .url(
            serverUrl.trim().trimEnd('/')
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://") + "/v1/calls/$callId/ws",
        )
        .header("Authorization", "Bearer $token")
        .build()

    private val client = NetClients.ws()
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed.get() || webSocket !== socket) {
                webSocket.close(1000, null)
                return
            }
            onState(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed.get() || webSocket !== socket) return
            onFrame(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed.get() || webSocket !== socket) return
            onState(false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed.get() || webSocket !== socket) return
            onState(false)
        }
    }

    fun connect() {
        if (closed.get()) return
        val prev = socket
        socket = client.newWebSocket(request, listener)
        prev?.cancel()
    }

    // false — сокет не поднят или закрыт; вызывающий не роняет звонок
    fun send(data: ByteArray): Boolean {
        val s = socket ?: return false
        if (closed.get()) return false
        return s.send(ByteString.of(*data))
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.let { cur ->
            socket = null
            cur.close(1000, null)
            cur.cancel()
        }
    }
}
