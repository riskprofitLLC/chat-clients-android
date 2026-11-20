package chat.client.domain

// Прокси для соединения с релеем (Фаза AA5, аналог proxy у Telegram):
// HTTP или SOCKS5, хост и порт. Чистая логика: валидация и маппинг
// на java.net.Proxy — JVM-тесты без сети.
data class ProxyConfig(val host: String, val port: Int, val socks: Boolean) {

    fun proxy(): java.net.Proxy =
        java.net.Proxy(
            if (socks) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP,
            // createUnresolved: DNS остаётся за OkHttp, конфигурация
            // прокси не делает сетевых вызовов сама
            java.net.InetSocketAddress.createUnresolved(host, port),
        )

    companion object {
        const val TYPE_HTTP = "http"
        const val TYPE_SOCKS5 = "socks5"
        const val MAX_HOST = 253

        // из настроек: null — выключен; мусор хоста/порта — null
        fun parse(enabled: Boolean, host: String, port: String, type: String): ProxyConfig? {
            if (!enabled) return null
            val h = host.trim().lowercase()
            if (h.isEmpty() || h.length > MAX_HOST) return null
            val p = port.trim().toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            val socks = when (type) {
                TYPE_SOCKS5 -> true
                TYPE_HTTP -> false
                else -> return null
            }
            return ProxyConfig(h, p, socks)
        }

        fun typeKey(socks: Boolean): String = if (socks) TYPE_SOCKS5 else TYPE_HTTP
    }
}
