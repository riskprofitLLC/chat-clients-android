package chat.client.domain

// Фаза GC: relay-фолбэк для симметричных NAT. Если P2P/STUN не собрался
// за FALLBACK_MS, оба конца шлют ICE-кандидат локального UDP-моста
// (127.0.0.1:BRIDGE_PORT); пакеты WebRTC едут через WS-пайп релея
// (DTLS/SRTP opaque для сервера). Порт фиксированный: кандидат ссылается
// на localhost принимающего, поэтому мост на обоих устройствах обязан
// слушать один и тот же порт.
object CallRelay {
    // порт локального моста; одинаковый на обоих концах звонка
    const val BRIDGE_PORT = 41923

    // через сколько без ICE CONNECTED включаем relay-путь
    const val FALLBACK_MS = 5_000L

    // host-кандидат локального моста (приоритет как у обычного host)
    fun bridgeCandidate(port: Int = BRIDGE_PORT): String =
        "candidate:0 1 UDP 2122815487 127.0.0.1 $port typ host"

    // relay-кандидат моста среди обычных (loopback + typ host)
    fun isBridgeCandidate(sdp: String): Boolean =
        sdp.contains(" 127.0.0.1 ") && sdp.contains("typ host")

    // пора ли включать relay-путь: ICE ещё не сошёлся и окно прошло
    fun shouldFallback(iceConnected: Boolean, elapsedMs: Long): Boolean =
        !iceConnected && elapsedMs >= FALLBACK_MS
}
