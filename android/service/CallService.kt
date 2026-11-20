package chat.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

// Фаза GB: FGS-сервис звонка (тип microphone) — стартует по accept
// (не при ринге, как решено в плане), держит микрофон в фоне и
// показывает CATEGORY_CALL-уведомление с кнопкой завершения.
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            (application as? chat.client.ChatApplication)?.container?.callManager?.hangUp()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALL,
                    getString(chat.client.R.string.call_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(chat.client.R.string.call_channel_desc)
                    setSound(null, null)
                },
            )
        }
        val end = PendingIntent.getService(
            this, 1,
            Intent(this, CallService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, chat.client.MainActivity::class.java).putExtra(EXTRA_OPEN_CALL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // B7: Notification.Builder(channel) — API 26; на 24/25 канал не нужен
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_CALL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(chat.client.R.drawable.ic_notification)
            .setContentTitle(getString(chat.client.R.string.call_ongoing))
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null as? android.graphics.drawable.Icon ?: android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(chat.client.R.string.call_end),
                    end,
                ).build(),
            )
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_CALL = "call_ongoing"
        const val ACTION_END = "chat.client.action.CALL_END"
        const val EXTRA_OPEN_CALL = "call_open"
        private const val NOTIF_ID = 51

        // микрофон живёт только пока звонок: менеджер сам просит старт/стоп.
        // B7: startForegroundService — API 26; на 24/25 — обычный startService
        fun start(context: Context) {
            val intent = Intent(context, CallService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
