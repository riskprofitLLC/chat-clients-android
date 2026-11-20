package chat.client.service

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import chat.client.ChatApplication
import chat.client.data.SessionInfo
import chat.client.domain.DirectReply
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Быстрый ответ и «Прочитано» из уведомления (Фаза K1). goAsync даёт
// ~10 с на IO-работу; процесс мог быть убит — сессию восстанавливаем
// с диска (как BackgroundSyncWorker). Отправка — через outbox: при
// сбое сети запись остаётся в очереди и уйдёт позже.
class NotificationReceiver : BroadcastReceiver() {

    companion object {
        // тот же паттерн, что и валидация peer в MainActivity
        private val chatIdRe = Regex("^([a-z0-9_-]{3,32}|grp_[a-z0-9_-]{1,48})$")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(Notifier.EXTRA_CHAT) ?: return
        if (!chatIdRe.matches(chatId)) return
        when (intent.action) {
            Notifier.ACTION_REPLY -> {
                // кривые extras не должны ронять процесс
                val raw = try {
                    RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY_TEXT)
                } catch (_: Exception) {
                    null
                }
                val text = DirectReply.prepare(raw) ?: return // пустой ввод — не ответ
                runAsync(context) { handleReply(it, chatId, text) }
            }
            Notifier.ACTION_READ -> runAsync(context) { handleRead(it, chatId) }
            // Фаза AF9: мьют чата навсегда + гашение уведомления
            Notifier.ACTION_MUTE -> runAsync(context) { handleMute(it, chatId) }
        }
    }

    // короткая фоновая работа ресивера: finish в finally
    private fun runAsync(context: Context, block: suspend (Context) -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block(context)
            } finally {
                pending.finish()
            }
        }
    }

    // сессия: живая или восстановленная с диска (процесс убит)
    private fun sessionOf(context: Context): SessionInfo? {
        val container = ChatApplication.containerOf(context)
        if (container.currentSession() == null) {
            try {
                container.sessionRepo.restoreSession()
            } catch (_: Exception) {
            }
        }
        return container.currentSession()
    }

    private suspend fun handleReply(context: Context, chatId: String, text: String) {
        val container = ChatApplication.containerOf(context)
        val s = sessionOf(context) ?: return // нет аккаунта — уведомление устарело
        try {
            container.outbox.warmup() // свежий процесс: очередь ещё не на диске в памяти
        } catch (_: Exception) {
        }
        // 3.3: null — гейт отклонил (канал/медленный режим) или текст пуст;
        // в обоих случаях честно показываем «не доставлено», молчание
        // выглядело бы отправкой
        val entry = chat.client.media.OutboxSender.addText(container, s, chatId, text)
        var delivered = false
        if (entry != null) {
            try {
                delivered = container.outbox.flushAll(s) > 0
            } catch (_: Exception) {
            }
        }
        // ответ = сообщения прочитаны: локально и для автора (только 1:1)
        container.unread.markRead(chatId)
        container.invalidateChats(setOf(chatId))
        if (chatId != s.login && container.groups.byId(chatId) == null) {
            try {
                s.api.confirmRead(s.token, chatId)
            } catch (_: Exception) {
            }
        }
        Notifier(context.applicationContext as Application, container)
            .notifyReplied(s, chatId, text, delivered)
    }

    private suspend fun handleRead(context: Context, chatId: String) {
        val container = ChatApplication.containerOf(context)
        container.unread.markRead(chatId)
        container.invalidateChats(setOf(chatId))
        val s = sessionOf(context)
        if (s != null && chatId != s.login && container.groups.byId(chatId) == null) {
            try {
                s.api.confirmRead(s.token, chatId)
            } catch (_: Exception) {
            }
        }
        container.connection.cancelNotification(chatId)
    }

    // «Без звука» (Фаза AF9): мьют навсегда, уведомление гасим
    private suspend fun handleMute(context: Context, chatId: String) {
        val container = ChatApplication.containerOf(context)
        withContext(Dispatchers.IO) {
            container.chatPrefs.setMutedUntil(chatId, Long.MAX_VALUE)
        }
        container.invalidateChats(setOf(chatId))
        container.connection.cancelNotification(chatId)
    }
}
