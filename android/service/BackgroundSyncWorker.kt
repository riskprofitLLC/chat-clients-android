package chat.client.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chat.client.ChatApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// Запасная фоновая доставка: периодический дрейн раз в 15 минут при
// наличии сети. Основной путь — ConnectionManager; worker страхует,
// когда процесс убит и foreground-сервис ограничен системой.
class BackgroundSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? ChatApplication)?.container ?: return Result.success()
        withContext(Dispatchers.IO) {
            // сессии ещё нет — восстанавливаем с диска (процесс убит)
            if (container.currentSession() == null) {
                try {
                    container.sessionRepo.restoreSession()
                } catch (_: Exception) {
                }
            }
            val s = container.currentSession() ?: return@withContext
            try {
                container.outbox.warmup() // процесс могли убить до фонового старта (C3)
                val report = container.messageRepo.drain(applicationContext, s)
                if (report.byChat.isNotEmpty()) {
                    container.connection.notifyAfterBackgroundDrain(s, report.byChat, report.mentions, report.silentChats, report.reactionNotices)
                }
                // фоновый дрейн меняет историю — список должен обновиться (A1)
                if (report.saved > 0 || report.transfers > 0) {
                    container.invalidateChats(null)
                }
                for ((chatId, info) in report.mediaToFetch) {
                    // Фаза X5: политика сети — всегда / Wi-Fi / никогда;
                    // Фаза Y3: автосейв в галерею — по настройке чата
                    container.messageRepo.fetchMediaAuto(applicationContext, s, chatId, info)
                }
                container.outbox.flushAll(s)
                // Фаза AE1: зачистка старых локальных медиа по политике
                container.sweepAgedMedia()
            } catch (e: Exception) {
                // токен отозван/устарел: сессия мертва — сбрасываем её,
                // иначе фон молча листал бы в пустоту, а приложение
                // открывалось с мёртвой сессией (как в ConnectionManager)
                if (e is chat.client.net.RelayException && (e.code == 401 || e.code == 403)) {
                    // мёртвый токен чистим и на диске: иначе следующий старт
                    // процесса снова поднимет его из префов
                    chat.client.data.Session.logout(applicationContext)
                    container.setSessionState(chat.client.data.SessionState.NoSession)
                    container.connection.stop()
                }
            }
        }
        return Result.success()
    }

    companion object {
        // уникальная задача: KEEP не сбрасывает ритм повторов
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "chat-bg-drain",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        // будильник отложенной отправки: разово проснуться к моменту и
        // прогнать outbox (процесс мог быть убит — WorkManager добудет)
        fun ensureWake(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<BackgroundSyncWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "chat-sched-wake",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
