package chat.client.net

import chat.client.domain.ProxyConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Общие OkHttp-клиенты процесса (Фаза AA5): один стек на JSON, медиа и
// WebSocket, чтобы прокси применялся ко всему трафику релея сразу.
// Смена прокси пересобирает клиенты; старые закрываются evictAll.
object NetClients {

    @Volatile private var proxy: ProxyConfig? = null

    @Volatile private var apiClient: OkHttpClient? = null
    @Volatile private var mediaClient: OkHttpClient? = null
    @Volatile private var wsClient: OkHttpClient? = null

    // применить/снять прокси; вызывается при старте и из настроек
    @Synchronized
    fun configure(cfg: ProxyConfig?) {
        if (proxy == cfg) return
        proxy = cfg
        val old = listOf(apiClient, mediaClient, wsClient)
        apiClient = null
        mediaClient = null
        wsClient = null
        // прежние пулы не держат соединения через сменный прокси
        old.forEach { c ->
            try {
                c?.connectionPool?.evictAll()
            } catch (_: Exception) {
            }
        }
    }

    // текущий конфиг — для настроек и тестов
    fun current(): ProxyConfig? = proxy

    // JSON-обмен: короткие таймауты
    @Synchronized
    fun api(): OkHttpClient = apiClient ?: OkHttpClient.Builder()
        .proxyOrNull()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
        .also { apiClient = it }

    // медиа: большие блобы до 10 МБ по медленной сети
    @Synchronized
    fun media(): OkHttpClient = mediaClient ?: api().newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()
        .also { mediaClient = it }

    // WebSocket wake: без read-таймаута, пинг 25с
    @Synchronized
    fun ws(): OkHttpClient = wsClient ?: OkHttpClient.Builder()
        .proxyOrNull()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
        .also { wsClient = it }

    private fun OkHttpClient.Builder.proxyOrNull(): OkHttpClient.Builder =
        apply { proxy?.let { proxy(it.proxy()) } }
}
