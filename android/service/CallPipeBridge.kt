package chat.client.service

import chat.client.domain.CallRelay
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

// Фаза GC: локальный UDP-мост между WebRTC и WS-пайпом релея.
// Relay-кандидат 127.0.0.1:BRIDGE_PORT заставляет WebRTC слать пакеты
// на этот сокет; мост перекидывает их в пайп. Входящие из пайпа уходят
// на адрес локального сокета WebRTC, выученный по последнему исходящему
// пакету (источник UDP-пакета = сокет PeerConnection).
class CallPipeBridge(
    private val port: Int = CallRelay.BRIDGE_PORT,
    private val sendToPipe: (ByteArray) -> Boolean,
) {
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    // адрес локального сокета WebRTC (куда слать входящие из пайпа)
    @Volatile private var localPeer: InetSocketAddress? = null

    // false — порт занят, relay-фолбэк недоступен (звонок не роняем)
    fun start(): Boolean {
        if (socket != null) return true
        val s = try {
            DatagramSocket(port, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            return false
        }
        socket = s
        thread = Thread {
            val buf = ByteArray(2048)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: Exception) {
                    break
                }
                localPeer = InetSocketAddress(p.address, p.port)
                sendToPipe(p.data.copyOf(p.length))
            }
        }.apply {
            isDaemon = true
            name = "call-pipe-bridge"
            start()
        }
        return true
    }

    // входящий кадр из пайпа — на локальный сокет WebRTC
    fun inject(data: ByteArray) {
        val s = socket ?: return
        val target = localPeer ?: return
        try {
            s.send(DatagramPacket(data, data.size, target))
        } catch (_: Exception) {
        }
    }

    fun stop() {
        socket?.close()
        socket = null
        localPeer = null
        thread = null
    }
}
