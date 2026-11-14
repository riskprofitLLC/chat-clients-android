package chat.client.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chat.client.ChatApplication
import chat.client.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// Foreground-сервис: держит процесс с соединением живым в фоне. Вся логика
// доставки — в ConnectionManager (общий пайплайн UI и сервиса, один WS).
// После рестарта системой (START_STICKY) сессия и соединение
// восстанавливаются здесь же (A15), текст статуса отражает реальное
// состояние соединения.
class ChatService : Service() {

    companion object {
        const val SERVICE_ID = 1001

        // 3.5: живая геопозиция в фоне требует FGS типа location; тип
        // поднимается/опускается вместе с активной трансляцией
        fun refreshFgsType(context: Context) {
            val intent = Intent().setComponent(android.content.ComponentName(context, ChatService::class.java))
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun start(context: Context) {
            // A11: на Android 14+ запуск FGS из фона легитимно падает
            // ForegroundServiceStartNotAllowedException — очередь доиграет
            // ConnectionManager/воркер, процесс ронять нельзя
            try {
                val intent = Intent().setComponent(android.content.ComponentName(context, ChatService::class.java))
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val intent = Intent().setComponent(android.content.ComponentName(context, ChatService::class.java))
            context.stopService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // текст статуса следует за реальным состоянием соединения (A15)
        scope.launch {
            ChatApplication.containerOf(this@ChatService).connection.connected.collect { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // тихое уведомление — обязательный атрибут foreground-сервиса;
        // 3.5: при активной живой геопозиции добавляем тип location —
        // иначе на Android 10+ координаты в фоне не читаются
        ServiceCompat.startForeground(
            this,
            SERVICE_ID,
            buildNotification(ChatApplication.containerOf(this).connection.connected.value),
            fgsTypes(),
        )
        // рестарт системой: сессию и соединение поднимаем сами (A15),
        // иначе «Чат подключён» висел бы без живого конвейера доставки
        val container = ChatApplication.containerOf(this)
        if (container.currentSession() == null) {
            Thread {
                try {
                    val state = container.sessionRepo.restoreSession()
                    (state as? chat.client.data.SessionState.Ready)?.let {
                        container.connection.start(it.info)
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
        return START_STICKY
    }

    // 3.5: тип FGS по текущему состоянию: dataSync всегда, location —
    // пока идёт трансляция геопозиции (и разрешение выдано)
    private fun fgsTypes(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val container = ChatApplication.containerOf(this)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val geoActive = container.liveGeo.active.value.isNotEmpty()
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (geoActive && granted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    // Android 14/15: лимит dataSync (6ч/24ч) — останавливаемся тихо,
    // ConnectionManager продолжает работать, пока жив процесс
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(state: chat.client.service.ConnectionManager.ConnState) {
        // вместе со статусом пересчитываем и тип FGS (3.5): старт/стоп
        // трансляции геопозиции отражается через refreshFgsType.
        // Лимит dataSync исчерпан — startForeground бросает исключение и роняет
        // процесс; ловим, сервис продолжит работать без FGS-уведомления
        try {
            ServiceCompat.startForeground(this, SERVICE_ID, buildNotification(state), fgsTypes())
        } catch (_: Exception) {
            try {
                stopForeground(STOP_FOREGROUND_DETACH)
            } catch (_: Exception) {
            }
        }
    }

    private fun buildNotification(state: chat.client.service.ConnectionManager.ConnState): Notification {
        val text = when (state) {
            chat.client.service.ConnectionManager.ConnState.Online -> getString(R.string.notif_service_text)
            chat.client.service.ConnectionManager.ConnState.Connecting -> getString(R.string.connecting)
            chat.client.service.ConnectionManager.ConnState.Offline -> getString(R.string.offline_banner)
        }
        return NotificationCompat.Builder(this, Notifier.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
