package chat.client.service

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import chat.client.MainActivity
import chat.client.R
import chat.client.data.AppContainer
import chat.client.data.Session
import chat.client.data.SessionInfo
import chat.client.domain.Markup
import chat.client.domain.MessageCodec
import chat.client.domain.ContactName

// Уведомления: каналы, входящие сообщения, новые устройства. Постит
// ConnectionManager и фоновый worker; ChatService держит только тихий
// foreground-статус на канале CONNECTION. MessagingStyle с последними
// записями чата, либо простой текст при скрытом содержимом.
// Фаза K1: у каждого сообщения — действия «Ответить» (RemoteInput,
// обрабатывает NotificationReceiver) и «Прочитано».
class Notifier(private val app: Application, private val container: AppContainer) {

    companion object {
        const val CHANNEL_MESSAGES = "chat_messages" // базовый id — тон «системный»
        // Фаза X1: сообщения, отправленные без звука — отдельный тихий канал
        const val CHANNEL_SILENT = "chat_messages_silent"
        const val CHANNEL_CALL_RING = "call_ring"
        private const val NOTIF_TAG_CALL = "call"
        const val CHANNEL_CONNECTION = "chat_connection"
        const val NOTIFICATION_TAG = "chat"

        // Фаза AA4 (по образцу Telegram): звук канала после создания
        // неизменяем — каждому тону СВОЙ канал, устаревшие удаляем
        private val CHANNEL_MESSAGES_ALL = listOf(
            CHANNEL_MESSAGES,
            "chat_messages_none",
            "chat_messages_soft",
            "chat_messages_bell",
            "chat_messages_pop",
        )

        fun messagesChannelId(soundKey: String): String =
            chat.client.domain.NotifyChannels.messageChannelId(soundKey)

        // отдельный id от чатов: не перетирает их уведомления
        private const val ID_NEW_DEVICE = 2001

        // действия быстрого ответа (K1); chatId едет в extra
        const val ACTION_REPLY = "chat.client.action.NOTIF_REPLY"
        const val ACTION_READ = "chat.client.action.NOTIF_READ"
        // Фаза AF9: мьют чата прямо из уведомления
        const val ACTION_MUTE = "chat.client.action.NOTIF_MUTE"
        const val EXTRA_CHAT = "chat_id"
        const val KEY_REPLY_TEXT = "reply_text"
    }

    // каналы сообщений/соединения; идемпотентно, эффект только на API≥26.
    // 3.10: каждому тону — своё различимое имя (в настройках системы
    // прежде все назывались одинаково); повторное создание существующих
    // убрано — звук канала после создания всё равно не меняется
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nameForSound = mapOf(
            chat.client.domain.NotificationSound.SYSTEM to R.string.channel_messages,
            chat.client.domain.NotificationSound.NONE to R.string.channel_messages_none,
            chat.client.domain.NotificationSound.SOFT to R.string.channel_messages_soft,
            chat.client.domain.NotificationSound.BELL to R.string.channel_messages_bell,
            chat.client.domain.NotificationSound.POP to R.string.channel_messages_pop,
        )
        for (id in CHANNEL_MESSAGES_ALL) {
            val nsound = when (id) {
                CHANNEL_MESSAGES -> chat.client.domain.NotificationSound.SYSTEM
                "chat_messages_none" -> chat.client.domain.NotificationSound.NONE
                "chat_messages_soft" -> chat.client.domain.NotificationSound.SOFT
                "chat_messages_bell" -> chat.client.domain.NotificationSound.BELL
                "chat_messages_pop" -> chat.client.domain.NotificationSound.POP
                else -> chat.client.domain.NotificationSound.SYSTEM
            }
            if (manager.getNotificationChannel(id) != null) continue
            manager.createNotificationChannel(
                NotificationChannel(
                    id,
                    app.getString(nameForSound[nsound] ?: R.string.channel_messages),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = app.getString(R.string.channel_messages_desc)
                    applySoundForKey(nsound, this)
                },
            )
        }
        // Фаза X1 (аналог isSilentMessage у Telegram): тихие сообщения
        // приходят в шторку без звука, вибрации и heads-up
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                app.getString(R.string.channel_silent),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = app.getString(R.string.channel_silent_desc)
                setSound(null, null)
                enableVibration(false)
            },
        )
        // тихий канал foreground-статуса — без бейджа
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION,
                app.getString(R.string.channel_connection),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = app.getString(R.string.channel_connection_desc)
                setShowBadge(false)
            },
        )
        // Отдельные каналы чатов с собственной важностью (Telegram-подобно):
        // создаём недостающие, удаляем осиротевшие
        syncChatChannels(manager)
        // Фаза GB: канал входящего звонка — рингтон + вибрация,
        // heads-up с fullScreenIntent (иначе NotificationService молча дропает)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL_RING,
                app.getString(R.string.call_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = app.getString(R.string.call_channel)
                setSound(
                    android.provider.Settings.System.DEFAULT_RINGTONE_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build(),
                )
                enableVibration(true)
            },
        )
    }

    // звук канала сообщений по ключу (Фаза AA4): «системный» —
    // тон устройства, «без звука» — тишина, остальное — встроенные тоны.
    // Вызывается только из createChannels — там уже отсеяно <26
    @androidx.annotation.RequiresApi(26)
    private fun applySoundForKey(soundKey: String, channel: NotificationChannel) {
        when (soundKey) {
            chat.client.domain.NotificationSound.NONE -> channel.setSound(null, null)
            chat.client.domain.NotificationSound.SOFT ->
                channel.setSound(soundUri("n_soft"), null)
            chat.client.domain.NotificationSound.BELL ->
                channel.setSound(soundUri("n_bell"), null)
            chat.client.domain.NotificationSound.POP ->
                channel.setSound(soundUri("n_pop"), null)
            else -> channel.setSound(
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null,
            )
        }
    }

    // Чаты с важностью HIGH/LOW получают свой канал: уровень и тон берутся
    // из настроек чата. Возврат на «как в настройках» удаляет канал — иначе
    // они копятся в системных настройках уведомлений
    @androidx.annotation.RequiresApi(26)
    private fun syncChatChannels(manager: NotificationManager) {
        val live = HashSet<String>()
        for (chatId in container.chatPrefs.chatsWithImportance()) {
            val importance = container.chatPrefs.importance(chatId)
            if (!chat.client.domain.ChatImportance.needsOwnChannel(importance)) continue
            val sound = container.chatPrefs.notifySound(chatId)
                ?: container.appPrefs.notificationSound()
            val id = chat.client.domain.NotifyChannels.chatChannelId(chatId, importance, sound)
            live += id
            if (manager.getNotificationChannel(id) != null) continue
            manager.createNotificationChannel(
                NotificationChannel(id, chatChannelName(chatId), systemImportance(importance)).apply {
                    description = app.getString(R.string.channel_messages_desc)
                    applySoundForKey(sound, this)
                    if (importance == chat.client.domain.ChatImportance.LOW) enableVibration(false)
                },
            )
        }
        for (existing in manager.notificationChannels) {
            if (!chat.client.domain.NotifyChannels.isChatChannel(existing.id)) continue
            if (existing.id in live) continue
            manager.deleteNotificationChannel(existing.id)
        }
    }

    @androidx.annotation.RequiresApi(26)
    private fun systemImportance(key: String): Int = when (key) {
        chat.client.domain.ChatImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }

    // имя отдельного канала чата в системных настройках уведомлений
    private fun chatChannelName(chatId: String): String = app.getString(
        R.string.channel_chat_name,
        container.chatPrefs.name(chatId) ?: container.groups.byId(chatId)?.name ?: chatId,
    )

    private fun soundUri(raw: String): android.net.Uri =
        android.net.Uri.parse("android.resource://${app.packageName}/raw/$raw")

    // API≥33 требует явного разрешения
    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // уведомления по чатам после дрейна; foreground и мьют фильтруют.
    // Фаза V4 (аналог mention-роутинга Telegram): @упоминание меня
    // в мьютнутой ГРУППЕ всё равно дёргает уведомление — иначе в чатной
    // группе можно пропустить обращение к себе; мьют 1:1 остаётся
    // абсолютным (личное сообщение и так всё для меня).
    fun notifyIncoming(
        session: SessionInfo,
        byChat: Map<String, Int>,
        mentions: Set<String> = emptySet(),
        foreground: Boolean,
        // Фаза X1: чаты с беззвучным последним сообщением
        silentChats: Map<String, Boolean> = emptyMap(),
    ) {
        // на переднем плане события показывает сам UI
        if (foreground || !canNotify()) return
        val manager = NotificationManagerCompat.from(app)
        val quietNow = quietActiveNow()
        for ((chatId, count) in byChat) {
            if (!chatNotifiable(chatId, chatId in mentions)) continue
            try {
                val silent = silentChats[chatId] == true || quietNow
                manager.notify(
                    NOTIFICATION_TAG,
                    chatId.hashCode(),
                    incomingBuilder(session, chatId, count, silent).build(),
                )
            } catch (_: SecurityException) {
            }
        }
    }

    // Фаза GB: входящий звонок — CATEGORY_CALL + full screen intent;
    // звук/вибрация — канал по умолчанию, экран звонка поверх приложения
    fun notifyIncomingCall(s: SessionInfo, chatId: String) {
        if (!canNotify()) return
        try {
            val title = chatTitle(s, chatId)
            val fullScreen = PendingIntent.getActivity(
                app, 61,
                Intent(app, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(CallService.EXTRA_OPEN_CALL, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(app, CHANNEL_CALL_RING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.call_incoming_title))
                .setContentText(title)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(contentIntent(chatId))
                // A9: на заблокированном экране имя звонящего скрыто —
                // публичная версия без заголовка чата (как у сообщений)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(app, CHANNEL_CALL_RING)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(app.getString(R.string.call_incoming_title))
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build(),
                )
            NotificationManagerCompat.from(app).notify(
                NOTIF_TAG_CALL, chat.client.domain.CallRing.notifId(chatId), builder.build(),
            )
        } catch (_: SecurityException) {
        }
    }

    // входящий звонок отменён — шторку убираем; id считается так же,
    // как при постинге (иначе ринг висит до перезагрузки)
    fun cancelIncomingCall(chatId: String?) {
        try {
            NotificationManagerCompat.from(app).cancel(
                NOTIF_TAG_CALL, chatId?.let { chat.client.domain.CallRing.notifId(it) } ?: 0,
            )
        } catch (_: SecurityException) {
        }
    }

    // AL9 (аналог «Reacted to your message» у Telegram): реакции на мои
    // сообщения — отдельное уведомление по чату; мьют и скрытые чаты
    // гасятся теми же гейтами, скрытый — без имени и эмодзи
    fun notifyReactions(
        s: SessionInfo,
        notices: List<chat.client.domain.ReactionNotify.Notice>,
        foreground: Boolean,
    ) {
        // на переднем плане события показывает сам UI
        if (foreground || !canNotify() || notices.isEmpty()) return
        val manager = NotificationManagerCompat.from(app)
        val quietNow = quietActiveNow()
        for (n in notices) {
            if (!chatNotifiable(n.chatId, mentioned = false)) continue
            try {
                manager.notify(
                    NOTIFICATION_TAG,
                    n.chatId.hashCode(),
                    reactionBuilder(s, n, quietNow).build(),
                )
            } catch (_: SecurityException) {
            }
        }
    }

    // сборка уведомления о реакции — отдельно для инструментальных тестов
    internal fun reactionBuilder(
        s: SessionInfo,
        n: chat.client.domain.ReactionNotify.Notice,
        quietNow: Boolean = false,
    ): NotificationCompat.Builder {
        // AH4: скрытый чат не раскрывает ни имя, ни эмодзи
        val hidden = container.chatPrefs.hidden(n.chatId)
        val title = if (hidden) {
            app.getString(R.string.hidden_chat_label)
        } else {
            chatTitle(s, n.chatId)
        }
        val builder = baseBuilder(s, n.chatId, 0, title).setNumber(0)
        if (quietNow) {
            builder.setChannelId(CHANNEL_SILENT)
            builder.setSilent(true)
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        } else if (Build.VERSION.SDK_INT < 26) {
            applyLegacySound(builder)
        }
        if (hidden) {
            builder.setContentText(app.getString(R.string.hidden_notification))
        } else {
            val authorDisplay = chat.client.domain.ContactName.display(
                n.author, container.chatPrefs.name(n.author),
            )
            builder.setContentText(
                if (n.chatId == n.author) {
                    // 1:1: имя уже в заголовке — только эмодзи
                    app.getString(R.string.reaction_notif_direct, n.emoji)
                } else {
                    app.getString(R.string.reaction_notif_group, authorDisplay, n.emoji)
                },
            )
        }
        return builder
    }

    // Фаза AA2: мьют и «только упоминания» в одном месте — для тестов.
    // Упоминание пробивает оба режима (V4)
    internal fun chatNotifiable(chatId: String, mentioned: Boolean): Boolean {
        if (container.chatPrefs.muted(chatId) && !mentioned) return false
        val onlyMentions = chatId.startsWith("grp_") &&
            container.chatPrefs.notifyMode(chatId) ==
            chat.client.data.ChatPrefsStore.NOTIFY_MENTIONS
        return !(onlyMentions && !mentioned)
    }

    // Фаза AA3: тихие часы активны прямо сейчас
    internal fun quietActiveNow(): Boolean = chat.client.domain.QuietHours.active(
        container.appPrefs.quietHoursEnabled(),
        container.appPrefs.quietFromMin(),
        container.appPrefs.quietToMin(),
        System.currentTimeMillis(),
    )

    // сборка уведомления чата: каркас + содержимое; отдельно от notify —
    // для проверки действий в инструментальных тестах (K1)
    internal fun incomingBuilder(
        s: SessionInfo,
        chatId: String,
        count: Int,
        silent: Boolean = false, // Фаза X1: тихий канал без звука/вибрации
    ): NotificationCompat.Builder {
        // AH4: скрытый чат не раскрывает ни имя, ни текст — только факт
        val hidden = container.chatPrefs.hidden(chatId)
        val title = if (hidden) {
            app.getString(R.string.hidden_chat_label)
        } else {
            chatTitle(s, chatId)
        }
        val builder = baseBuilder(s, chatId, count, title)
        if (silent) {
            // тихий канал + setSilent: двойная гарантия на всех API —
            // канал задаёт звук/вибрацию (26+), setSilent глушит
            // уведомление и на 24–25 через приоритет
            builder.setChannelId(CHANNEL_SILENT)
            builder.setSilent(true)
            builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        } else if (Build.VERSION.SDK_INT < 26) {
            // Фаза AA4: на 24–25 каналов нет — выбранный тон напрямую
            applyLegacySound(builder)
        }
        if (hidden) {
            builder.setContentText(app.getString(R.string.hidden_notification))
        } else if (chat.client.domain.ChatNotificationContent.hideText(
                container.chatPrefs.contentOverride(chatId),
                container.appPrefs.hideNotificationText(),
            )
        ) {
            builder.setContentText(plainText(count))
        } else {
            val style = chatStyle(s, chatId, title)
            if (style != null) builder.setStyle(style) else builder.setContentText(plainText(count))
        }
        return builder
    }

    // обновление после быстрого ответа (K1): ответ показывается в стиле
    // от «меня»; если доставка не прошла — текст добавлен вручную, без
    // бейджа непрочитанных (ответ = сообщения прочитаны)
    fun notifyReplied(s: SessionInfo, chatId: String, replyText: String, delivered: Boolean) {
        if (!canNotify()) return
        // AH4: ответ в скрытом чате не раскрывает его содержание
        val hidden = container.chatPrefs.hidden(chatId)
        val title = if (hidden) {
            app.getString(R.string.hidden_chat_label)
        } else {
            chatTitle(s, chatId)
        }
        val builder = baseBuilder(s, chatId, 0, title).setOnlyAlertOnce(true)
        // перс.тон: убеждаемся, что канал существует для этого чата
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannels()
            builder.setChannelId(chatChannelId(chatId))
        } else {
            applyLegacySound(builder)
        }
        if (hidden) {
            builder.setContentText(app.getString(R.string.notif_replied_short))
        } else if (chat.client.domain.ChatNotificationContent.hideText(
                container.chatPrefs.contentOverride(chatId),
                container.appPrefs.hideNotificationText(),
            )
        ) {
            builder.setContentText(app.getString(R.string.notif_replied_short))
        } else {
            val style = chatStyle(s, chatId, title) ?: NotificationCompat.MessagingStyle(mePerson(s))
            if (!delivered) style.addMessage(replyText, System.currentTimeMillis(), null as Person?)
            builder.setStyle(style)
        }
        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_TAG, chatId.hashCode(), builder.build())
        } catch (_: SecurityException) {
        }
    }

    // каркас: заголовок, тап в чат, действия ответа и «прочитано» (K1)
    // Канал чата: своя важность (ChatImportance) — отдельный канал со своим
    // уровнем и тоном, иначе общий канал выбранного тона (AB4)
    fun chatChannelId(chatId: String): String =
        chat.client.domain.NotifyChannels.chatChannelId(
            chatId,
            container.chatPrefs.importance(chatId),
            container.chatPrefs.notifySound(chatId) ?: container.appPrefs.notificationSound(),
        )

    // AK5 (аналог largeIcon у Telegram): аватар чата крупной иконкой
    // уведомления. Только локальная копия — сеть в путях уведомлений
    // не блокируем; нет кэша — системная мелкая иконка, как прежде
    internal fun avatarLargeIcon(s: SessionInfo, chatId: String): android.graphics.Bitmap? {
        val info = try {
            container.avatars.groupOf(chatId) ?: container.avatars.userOf(chatId)
        } catch (_: Exception) {
            null
        } ?: return null
        if (chatId == s.login) return null // «Избранное» — без аватара
        val bytes = try {
            chat.client.media.Media.load(app, info.id)
        } catch (_: Exception) {
            null
        } ?: return null
        val src = try {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } ?: return null
        // квадрат по центру + скругление, как в аватарке списка
        val edge = 256
        val scale = edge.toFloat() / maxOf(src.width, src.height, 1)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
        val square = android.graphics.Bitmap.createBitmap(edge, edge, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(square)
        // большая сторона = edge, меньшая центрируется (смещение ≥ 0)
        canvas.drawBitmap(
            scaled,
            (edge - w) / 2f,
            (edge - h) / 2f,
            null,
        )
        val rounded = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(app.resources, square)
        rounded.cornerRadius = edge * 0.22f
        val out = android.graphics.Bitmap.createBitmap(edge, edge, android.graphics.Bitmap.Config.ARGB_8888)
        rounded.setBounds(0, 0, edge, edge)
        rounded.draw(android.graphics.Canvas(out))
        return out
    }

    private fun baseBuilder(
        s: SessionInfo,
        chatId: String,
        count: Int,
        title: String = chatTitle(s, chatId),
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(app, chatChannelId(chatId))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setNumber(count)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // A9: на заблокированном экране текст сообщений скрыт;
            // публичная версия — «Новое сообщение», без содержимого
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion().build())
            .setContentIntent(contentIntent(chatId))
            .addAction(replyAction(chatId))
            .addAction(readAction(chatId))
            // Фаза AF9: третье действие — без звука, как у групповых
            // уведомлений Telegram: глушим чат не открывая приложения
            .addAction(muteAction(chatId))
        // AK5: аватар чата крупной иконкой; нет аватара — системная
        avatarLargeIcon(s, chatId)?.let { builder.setLargeIcon(it) }
        return builder
    }

    // A9: что видно на заблокированном экране — факт сообщения без текста
    private fun publicVersion(): NotificationCompat.Builder =
        NotificationCompat.Builder(app, messagesChannelId(container.appPrefs.notificationSound()))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.notif_public_title))
            .setAutoCancel(true)

    private fun chatTitle(s: SessionInfo, chatId: String): String = if (chatId == s.login) {
        app.getString(R.string.saved_messages)
    } else {
        // локальное имя поверх логина, группы — своим именем
        container.chatPrefs.name(chatId) ?: container.groups.byId(chatId)?.name ?: chatId
    }

    // действие «Ответить»: прямое поле ввода в шторке (K1).
    // PendingIntent обязан быть MUTABLE на API 31+ (A4): система
    // вкладывает результаты RemoteInput именно в mutable-интенты,
    // с FLAG_IMMUTABLE ответ молча не приходил
    private fun replyAction(chatId: String): NotificationCompat.Action {
        val label = app.getString(R.string.reply)
        val intent = Intent(app, NotificationReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_CHAT, chatId)
        val flagMutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            app,
            chatId.hashCode() * 2 + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagMutable,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_action_reply, label, pending)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel(label).build())
            .setAllowGeneratedReplies(true)
            .build()
    }

    // действие «Прочитано»: снять непрочитанные и сообщить автору (K1)
    private fun readAction(chatId: String): NotificationCompat.Action {
        val label = app.getString(R.string.notif_action_read)
        val intent = Intent(app, NotificationReceiver::class.java)
            .setAction(ACTION_READ)
            .putExtra(EXTRA_CHAT, chatId)
        val pending = PendingIntent.getBroadcast(
            app,
            chatId.hashCode() * 2 + 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_action_read, label, pending).build()
    }

    // действие «Без звука» (Фаза AF9): мьют чата навсегда
    private fun muteAction(chatId: String): NotificationCompat.Action {
        val label = app.getString(R.string.notif_action_mute)
        val intent = Intent(app, NotificationReceiver::class.java)
            .setAction(ACTION_MUTE)
            .putExtra(EXTRA_CHAT, chatId)
        val pending = PendingIntent.getBroadcast(
            app,
            chatId.hashCode() * 2 + 3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_action_mute, label, pending).build()
    }

    // привязка нового устройства аккаунта
    fun notifyNewDevice(deviceId: String) {
        if (!canNotify()) return
        val notif = NotificationCompat.Builder(app, messagesChannelId(container.appPrefs.notificationSound()))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.notif_new_device_title))
            .setContentText(app.getString(R.string.notif_new_device_text, deviceId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent(null))
            .build()
        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_TAG, ID_NEW_DEVICE, notif)
        } catch (_: SecurityException) {
        }
    }

    fun cancelNotification(chatId: String) {
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_TAG, chatId.hashCode())
    }

    // MessagingStyle: до 3 последних текстовых записей (не реакции).
    // Свои записи — от «меня» (K1), чужие — именем отправителя
    private class NotifLine(val from: String, val text: String, val ts: Long, val mine: Boolean)

    private fun chatStyle(s: SessionInfo, chatId: String, title: String): NotificationCompat.MessagingStyle? {
        val legacySince = container.appPrefs.legacyDecodeSince()
        val picked = ArrayList<NotifLine>()
        // история — по возрастанию, идём с конца
        for (record in s.store.history(chatId, 20).asReversed()) {
            val parsed = Session.parseRecord(record, legacySince)
            if (parsed !is MessageCodec.Parsed.Content) continue
            when (val kind = parsed.kind) {
                is MessageCodec.Kind.Text ->
                    picked.add(NotifLine(record.from, Markup.strip(kind.text).take(64), record.timestamp, record.from == s.login))
                is MessageCodec.Kind.Reply ->
                    picked.add(NotifLine(record.from, Markup.strip(kind.text).take(64), record.timestamp, record.from == s.login))
                else -> continue
            }
            if (picked.size == 3) break
        }
        if (picked.isEmpty()) return null
        val style = NotificationCompat.MessagingStyle(mePerson(s))
            .setConversationTitle(title)
        // стиль требует хронологический порядок — разворачиваем обратно
        for (line in picked.asReversed()) {
            style.addMessage(
                line.text,
                line.ts,
                // null-персон = «я» в MessagingStyle (K1); имя автора —
                // локальное, если задано
                if (line.mine) null
                else Person.Builder()
                    .setName(chat.client.domain.ContactName.display(line.from, container.chatPrefs.name(line.from)))
                    .build(),
            )
        }
        return style
    }

    // «я» в MessagingStyle: логин пользователя
    private fun mePerson(s: SessionInfo): Person = Person.Builder().setName(s.login).build()

    // нейтральный текст без содержимого: приватность или пустая история.
    // Plurals (A15): «1 новое сообщение / 2 новых сообщения / 5 новых»
    private fun plainText(count: Int): String =
        app.resources.getQuantityString(R.plurals.notif_new_messages_plural, count, count)

    // Фаза AA4: звук уведомления на 24–25 — каналов там ещё нет
    @Suppress("DEPRECATION")
    private fun applyLegacySound(builder: NotificationCompat.Builder) {
        val key = container.appPrefs.notificationSound()
        when (key) {
            chat.client.domain.NotificationSound.NONE -> Unit
            chat.client.domain.NotificationSound.SOFT ->
                builder.setSound(soundUri("n_soft"))
            chat.client.domain.NotificationSound.BELL ->
                builder.setSound(soundUri("n_bell"))
            chat.client.domain.NotificationSound.POP ->
                builder.setSound(soundUri("n_pop"))
            else -> builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        }
    }

    // переход в чат; peer=null — просто открыть приложение
    private fun contentIntent(peer: String?): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).apply {
            if (peer != null) putExtra("peer", peer)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            app,
            peer?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
