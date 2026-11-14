package chat.client.data

import android.content.Context
import java.security.MessageDigest

// Настройки приложения (не чатов): тема, приватность, внешний вид, блокировка.
// SharedPreferences. PIN-хэш хранится здесь ОТКРЫТО (не через SecretBox):
// это соль+PBKDF2-600k, из которого пароль не восстанавливается; привязка
// к Keystore не добавила бы защиты, а сломала бы вход при сбросе Keystore (A4).
class AppPrefs(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    // ─── внешний вид ───

    fun themeMode(): ThemeMode = try {
        ThemeMode.valueOf(prefs().getString("theme", ThemeMode.SYSTEM.name)!!)
    } catch (_: Exception) {
        ThemeMode.SYSTEM
    }

    fun setThemeMode(mode: ThemeMode) = prefs().edit().putString("theme", mode.name).apply()

    // GE3: целевой язык перевода сообщений (по умолчанию — язык системы)
    fun translateLang(): String =
        chat.client.domain.TranslateFormat.sanitizeLang(
            prefs().getString("translate_lang", java.util.Locale.getDefault().language) ?: "",
        ) ?: "en"

    fun setTranslateLang(lang: String) {
        chat.client.domain.TranslateFormat.sanitizeLang(lang)?.let {
            prefs().edit().putString("translate_lang", it).apply()
        }
    }

    fun dynamicColors(): Boolean = prefs().getBoolean("dynamic", false)

    fun setDynamicColors(value: Boolean) = prefs().edit().putBoolean("dynamic", value).apply()

    // Фаза Z6: AMOLED — чисто чёрные фоны в тёмном режиме (экономия
    // батареи на OLED); в светлом режиме флаг ничего не меняет
    fun amoled(): Boolean = prefs().getBoolean("amoled", false)

    fun setAmoled(value: Boolean) = prefs().edit().putBoolean("amoled", value).apply()

    // Фаза Z10: Enter — отправка сообщения (перенос строки выключен)
    fun enterSend(): Boolean = prefs().getBoolean("enter_send", false)

    fun setEnterSend(value: Boolean) = prefs().edit().putBoolean("enter_send", value).apply()

    // масштаб текста внутри приложения, проценты (85..130)
    fun textScale(): Int = prefs().getInt("text_scale", 100).coerceIn(85, 130)

    fun setTextScale(percent: Int) = prefs().edit().putInt("text_scale", percent.coerceIn(85, 130)).apply()

    // фон диалога: 0 — паттерн, 1 — однотонный (тихий Quiet по умолчанию)
    fun chatWallpaper(): Int = prefs().getInt("chat_wallpaper", 1)

    fun setChatWallpaper(value: Int) = prefs().edit().putInt("chat_wallpaper", value).apply()

    // ─── дизайн-направление (D1): строкой — слой данных не знает UI-типов ───

    fun designDirectionName(): String = prefs().getString("design_direction", "SOFT_CARDS") ?: "SOFT_CARDS"

    fun setDesignDirectionName(value: String) =
        prefs().edit().putString("design_direction", value).apply()

    // AH3 (аналог AccentColors у Telegram): палитра акцентов — строкой;
    // мусор читается как дефолтный EMERALD
    fun paletteName(): String = prefs().getString("palette", "EMERALD") ?: "EMERALD"

    fun setPaletteName(value: String) =
        prefs().edit().putString("palette", value).apply()

    // AH2 (аналог авто-ночного режима): окно тёмной темы в минутах от
    // полуночи; по умолчанию 22:00–07:00
    fun autoDarkFrom(): Int = prefs().getInt("auto_dark_from", 22 * 60)

    fun setAutoDarkFrom(minute: Int) =
        prefs().edit().putInt("auto_dark_from", minute.coerceIn(0, 24 * 60 - 1)).apply()

    fun autoDarkTo(): Int = prefs().getInt("auto_dark_to", 7 * 60)

    fun setAutoDarkTo(minute: Int) =
        prefs().edit().putInt("auto_dark_to", minute.coerceIn(0, 24 * 60 - 1)).apply()

    // ─── корневой таб нижней навигации (D2) ───

    // последний открытый раздел: чаты/контакты/настройки; помнится между
    // запусками, мусор читается как «Чаты»
    fun rootTab(): String = chat.client.domain.RootTabs.sanitize(prefs().getString("root_tab", null))

    fun setRootTab(tab: String) {
        if (!chat.client.domain.RootTabs.isValid(tab)) return
        prefs().edit().putString("root_tab", tab).apply()
    }

    // ─── папки чатов (Фаза L1) ───

    // JSON-массив папок; разбор и битость — в domain.ChatFolders
    fun foldersJson(): String? = prefs().getString("folders", null)

    fun setFoldersJson(raw: String) = prefs().edit().putString("folders", raw).apply()

    // Фаза AJ: ревизия папок для синка между своими устройствами
    fun foldersRev(): Long = prefs().getLong("folders_rev", 0L)

    fun setFoldersRev(value: Long) = prefs().edit().putLong("folders_rev", value).apply()

    // ─── приватность ───

    fun secureFlag(): Boolean = prefs().getBoolean("secure_flag", false)
    fun setSecureFlag(value: Boolean) = prefs().edit().putBoolean("secure_flag", value).apply()

    // скрывать текст сообщений в уведомлениях
    fun hideNotificationText(): Boolean = prefs().getBoolean("hide_notif_text", false)

    fun setHideNotificationText(value: Boolean) = prefs().edit().putBoolean("hide_notif_text", value).apply()

    // Фаза AJ: открывать ссылки во встроенном просмотре (как у Telegram);
    // выключено — прежний внешний браузер по подтверждению
    fun linksInApp(): Boolean = prefs().getBoolean("links_in_app", true)

    fun setLinksInApp(value: Boolean) = prefs().edit().putBoolean("links_in_app", value).apply()

    // Фаза AJ: порог автоудаления аккаунта при простое — зеркало
    // серверной настройки для мгновенного UI
    fun accountInactivityMonths(): Int = prefs().getInt("inactivity_months", 0)

    fun setAccountInactivityMonths(value: Int) =
        prefs().edit().putInt("inactivity_months", value).apply()

    // звуки в приложении (отправка/входящее) — как EnableInChatSound в Telegram
    fun inAppSounds(): Boolean = prefs().getBoolean("in_app_sounds", true)

    fun setInAppSounds(value: Boolean) = prefs().edit().putBoolean("in_app_sounds", value).apply()

    // Фаза AA3 (аналог тихих часов): ночью уведомления без звука — окно
    // в минутах от полуночи; from==to — выключено
    fun quietHoursEnabled(): Boolean = prefs().getBoolean("quiet_enabled", false)

    fun setQuietHoursEnabled(value: Boolean) = prefs().edit().putBoolean("quiet_enabled", value).apply()

    fun quietFromMin(): Int = prefs().getInt("quiet_from", 23 * 60)

    fun setQuietFromMin(min: Int) = prefs().edit().putInt("quiet_from", min.coerceIn(0, 24 * 60 - 1)).apply()

    fun quietToMin(): Int = prefs().getInt("quiet_to", 8 * 60)

    fun setQuietToMin(min: Int) = prefs().edit().putInt("quiet_to", min.coerceIn(0, 24 * 60 - 1)).apply()

    // GH5 (аналог Auto-Reply): автоответ в тихие часы на входящие 1:1
    fun autoReplyEnabled(): Boolean = prefs().getBoolean("autoreply_enabled", false)

    fun setAutoReplyEnabled(value: Boolean) = prefs().edit().putBoolean("autoreply_enabled", value).apply()

    fun autoReplyText(): String = prefs().getString("autoreply_text", null) ?: ""

    fun setAutoReplyText(text: String) = prefs().edit().putString("autoreply_text", text.take(200)).apply()

    // последний автоответ по чату: chatId -> unix сек
    fun autoReplyLastAt(chatId: String): Long = prefs().getLong("autoreply_last_$chatId", 0)

    fun setAutoReplyLastAt(chatId: String, atSec: Long) =
        prefs().edit().putLong("autoreply_last_$chatId", atSec).apply()

    // Фаза AA4 (аналог выбора звука уведомлений): ключ из domain.NotificationSound
    fun notificationSound(): String =
        chat.client.domain.NotificationSound.parse(prefs().getString("notif_sound", null))

    fun setNotificationSound(key: String) =
        prefs().edit().putString("notif_sound", key).apply()

    // Фаза AA5 (аналог proxy у Telegram): HTTP/SOCKS5 для релея;
    // настройка устройства — при сбросе аккаунта не трогается
    fun proxyEnabled(): Boolean = prefs().getBoolean("proxy_enabled", false)

    fun setProxyEnabled(value: Boolean) = prefs().edit().putBoolean("proxy_enabled", value).apply()

    fun proxyHost(): String = prefs().getString("proxy_host", "") ?: ""

    fun setProxyHost(value: String) = prefs().edit().putString("proxy_host", value.take(253)).apply()

    fun proxyPort(): String = prefs().getString("proxy_port", "") ?: ""

    fun setProxyPort(value: String) = prefs().edit().putString("proxy_port", value.take(5)).apply()

    fun proxyType(): String = prefs().getString("proxy_type", chat.client.domain.ProxyConfig.TYPE_HTTP) ?: chat.client.domain.ProxyConfig.TYPE_HTTP

    fun setProxyType(value: String) = prefs().edit().putString("proxy_type", value).apply()

    // Фаза AA6: момент последней автопроверки обновлений
    fun lastUpdateCheckAt(): Long = prefs().getLong("update_check_at", 0L)

    fun setLastUpdateCheckAt(at: Long) = prefs().edit().putLong("update_check_at", at).apply()

    // доступная версия с сайта беты: пусто — обновления нет
    fun pendingUpdateVersion(): String = prefs().getString("update_version", "") ?: ""

    fun setPendingUpdateVersion(version: String) =
        prefs().edit().putString("update_version", version).apply()

    fun pendingUpdateUrl(): String = prefs().getString("update_url", "") ?: ""

    fun setPendingUpdateUrl(url: String) = prefs().edit().putString("update_url", url).apply()

    // автозагрузка транзитного медиа по сети (Фаза X5): always | wifi | never;
    // настройка устройства — при сбросе аккаунта не трогается, как тема
    fun mediaAutoDownload(): chat.client.domain.MediaAutoDownload =
        chat.client.domain.MediaAutoDownload.parse(prefs().getString("media_autodl", null))

    fun setMediaAutoDownload(mode: chat.client.domain.MediaAutoDownload) =
        prefs().edit().putString("media_autodl", mode.key).apply()

    // AK3: потолок размера фоновых докачек, КБ (0 — без лимита)
    fun mediaAutoCapKb(): Int =
        chat.client.domain.MediaAutoCap.parse(prefs().getInt("media_autodl_cap", 0))

    fun setMediaAutoCapKb(kb: Int) =
        prefs().edit().putInt("media_autodl_cap", chat.client.domain.MediaAutoCap.parse(kb)).apply()

    // AK6: действия свайпа строки чата — на сторону; дефолты прежние
    fun swipeRight(): chat.client.domain.SwipeChoice =
        chat.client.domain.SwipeChoice.parse(
            prefs().getString("swipe_right", null),
            chat.client.domain.SwipeChoice.PIN,
        )

    fun setSwipeRight(choice: chat.client.domain.SwipeChoice) =
        prefs().edit().putString("swipe_right", choice.key).apply()

    fun swipeLeft(): chat.client.domain.SwipeChoice =
        chat.client.domain.SwipeChoice.parse(
            prefs().getString("swipe_left", null),
            chat.client.domain.SwipeChoice.MUTE,
        )

    fun setSwipeLeft(choice: chat.client.domain.SwipeChoice) =
        prefs().edit().putString("swipe_left", choice.key).apply()

    // AK2: прослушивание у уха (датчик приближения) — вкл по умолчанию
    fun earListening(): Boolean = prefs().getBoolean("ear_listen", true)

    fun setEarListening(value: Boolean) =
        prefs().edit().putBoolean("ear_listen", value).apply()

    // AI8 (аналог матрицы Auto-Download у Telegram): политика по типам —
    // фото/видео/файлы/голосовые; незаданный тип наследует глобальную
    fun mediaAutoTypes(): Map<chat.client.domain.MediaAutoKind, chat.client.domain.MediaAutoDownload> {
        val raw = LinkedHashMap<String, String?>()
        for (k in chat.client.domain.MediaAutoKind.entries) {
            raw["media_autodl_${k.key}"] = prefs().getString("media_autodl_${k.key}", null)
        }
        return chat.client.domain.MediaAutoDownloadPolicy.parseTypes(raw)
    }

    fun setMediaAutoType(kind: chat.client.domain.MediaAutoKind, mode: chat.client.domain.MediaAutoDownload) =
        prefs().edit().putString("media_autodl_${kind.key}", mode.key).apply()

    // недавние эмодзи панели (Фаза R1): JSON-массив, кодек — domain.EmojiRecents
    fun emojiRecentsJson(): String? = prefs().getString("emoji_recents", null)

    fun setEmojiRecentsJson(raw: String) = prefs().edit().putString("emoji_recents", raw).apply()

    // Фаза AJ: избранные эмодзи — долгий тап закрепляет во вкладке
    fun emojiFavoritesJson(): String? = prefs().getString("emoji_favorites", null)

    fun setEmojiFavoritesJson(raw: String) = prefs().edit().putString("emoji_favorites", raw).apply()

    // Фаза AG4 (аналог выбора тона кожи в панели эмодзи Telegram):
    // тон применяется к сетке панели и к выдаче поиска; 0 — без тона
    fun emojiTone(): Int {
        val raw = try {
            prefs().getInt("emoji_tone", chat.client.domain.EmojiTone.NONE)
        } catch (_: Exception) {
            chat.client.domain.EmojiTone.NONE
        }
        return chat.client.domain.EmojiTone.sanitize(raw)
    }

    fun setEmojiTone(value: Int) {
        val tone = chat.client.domain.EmojiTone.sanitize(value)
        prefs().edit().putInt("emoji_tone", tone).apply()
    }

    // Фаза Y5: недавние стикеры — id из локального набора, JSON-массив
    fun stickerRecentsJson(): String? = prefs().getString("sticker_recents", null)

    fun setStickerRecentsJson(raw: String) =
        prefs().edit().putString("sticker_recents", raw).apply()

    // AK8: избранные стикеры — кодек domain.StickerFavorites
    fun stickerFavoritesJson(): String? = prefs().getString("sticker_favorites", null)

    fun setStickerFavoritesJson(raw: String) =
        prefs().edit().putString("sticker_favorites", raw).apply()

    // Фаза AB3: прогресс голосовых (точка непрослушанного, продолжение
    // с места) — JSON-массив, кодек — domain.VoiceProgress
    fun voiceProgressJson(): String? = prefs().getString("voice_progress", null)

    fun setVoiceProgressJson(raw: String) =
        prefs().edit().putString("voice_progress", raw).apply()

    // Фаза AC10: опросы с просмотренными результатами — после
    // просмотра голосовать нельзя; JSON-массив, кодек — domain.PollViewed
    fun pollViewedJson(): String? = prefs().getString("poll_viewed", null)

    fun setPollViewedJson(raw: String) =
        prefs().edit().putString("poll_viewed", raw).apply()

    // Фаза AF2: позиции просмотра видео — кодек domain.VideoResume
    fun videoResumeJson(): String? = prefs().getString("video_resume", null)

    fun setVideoResumeJson(raw: String) =
        prefs().edit().putString("video_resume", raw).apply()

    // Фаза AG8: повтор обычного видео по кругу (кружки повторяются всегда);
    // настройка устройства
    fun videoLoop(): Boolean = prefs().getBoolean("video_loop", false)

    fun setVideoLoop(value: Boolean) = prefs().edit().putBoolean("video_loop", value).apply()

    // Фаза AB7: реакция по двойному тапу (аналог reaction_on_double_tap);
    // невалидное значение читается как дефолтный ❤
    fun quickReaction(): String =
        chat.client.domain.ReactionFormat.quickOrDefault(prefs().getString("quick_reaction", null))

    fun setQuickReaction(emoji: String) {
        // белый список набора реакций: чужие значения не пишем
        if (!chat.client.domain.ReactionFormat.isQuickValid(emoji)) return
        prefs().edit().putString("quick_reaction", emoji).apply()
    }

    // Фаза AD5: размер сжатого фото при отправке — помнит выбор
    // пользователя между отправками (аналог пресетов у Telegram)
    fun photoQuality(): Int =
        chat.client.domain.PhotoQuality.sanitize(prefs().getInt("photo_quality", chat.client.domain.PhotoQuality.DEFAULT))

    fun setPhotoQuality(target: Int) {
        if (target !in chat.client.domain.PhotoQuality.CHOICES) return
        prefs().edit().putInt("photo_quality", target).apply()
    }

    // Фаза AE1 (аналог «Keep media» у Telegram): срок хранения локальных
    // копий медиа, 0 — всегда; настройка устройства, при сбросе аккаунта живёт
    fun mediaKeepDays(): Int =
        chat.client.domain.MediaRetention.sanitize(prefs().getInt("media_keep_days", 0))

    fun setMediaKeepDays(days: Int) {
        prefs().edit().putInt("media_keep_days", chat.client.domain.MediaRetention.sanitize(days)).apply()
    }

    // Фаза AE6 (аналог privacy-тумблеров у Telegram): не отправлять
    // «печатает…» и «прочитано» — статус не едет на сервер вовсе
    fun hideTypingStatus(): Boolean = prefs().getBoolean("hide_typing", false)

    fun setHideTypingStatus(value: Boolean) =
        prefs().edit().putBoolean("hide_typing", value).apply()

    fun hideReadStatus(): Boolean = prefs().getBoolean("hide_read", false)

    fun setHideReadStatus(value: Boolean) =
        prefs().edit().putBoolean("hide_read", value).apply()

    // AI2: приватность статуса «в сети» — зеркало серверного флага,
    // истина живёт на сервере (он отвечает спрашивающим)
    fun hidePresence(): Boolean = prefs().getBoolean("hide_presence", false)

    fun setHidePresence(value: Boolean) = prefs().edit().putBoolean("hide_presence", value).apply()

    // Фаза AG3 (аналог Archive and Mute new chats): новые чаты от
    // незнакомцев сразу уходят в архив и без звука
    fun archiveMuteStrangers(): Boolean = prefs().getBoolean("archive_mute_strangers", false)

    fun setArchiveMuteStrangers(value: Boolean) =
        prefs().edit().putBoolean("archive_mute_strangers", value).apply()

    // ─── миграция протокола v2 ───

    // момент перехода на конверты v2: записи старше разбираются
    // и по строгим v1-схемам, новые входящие — только v2/текст
    fun legacyDecodeSince(): Long = prefs().getLong("legacy_since", 0L)

    fun ensureLegacySince(clock: Clock): Long {
        val existing = legacyDecodeSince()
        if (existing > 0) return existing
        val now = clock.now()
        prefs().edit().putLong("legacy_since", now).apply()
        return now
    }

    // ─── блокировка приложения ───

    fun lockEnabled(): Boolean = prefs().getBoolean("lock_enabled", false)

    fun setLockEnabled(value: Boolean) {
        prefs().edit().putBoolean("lock_enabled", value).apply()
        // A9: блокировка приложения прячет и текст уведомлений — на
        // заблокированном экране содержимое не должно быть видным
        if (value) prefs().edit().putBoolean("hide_notif_text", true).apply()
    }

    fun lockUseBiometric(): Boolean = prefs().getBoolean("lock_biometric", true)

    fun setLockUseBiometric(value: Boolean) = prefs().edit().putBoolean("lock_biometric", value).apply()

    // автоблокировка при уходе в фон, секунды; 0 — только при старте
    fun autoLockSec(): Int = prefs().getInt("auto_lock_sec", 0)

    fun setAutoLockSec(sec: Int) = prefs().edit().putInt("auto_lock_sec", sec).apply()

    // throttle неверных PIN переживает убийство процесса (A5)
    fun pinFailCount(): Int = prefs().getInt("pin_fails", 0)

    fun setPinFailCount(count: Int) = prefs().edit().putInt("pin_fails", count).apply()

    fun pinLockedUntil(): Long = prefs().getLong("pin_locked_until", 0L)

    fun setPinLockedUntil(until: Long) = prefs().edit().putLong("pin_locked_until", until).apply()

    fun pinSalt(): String? = prefs().getString("pin_salt", null)

    fun pinHash(): String? = prefs().getString("pin_hash", null)

    fun setPin(pin: String) {
        val salt = PinCrypto.newSalt()
        prefs().edit()
            .putString("pin_salt", salt)
            .putString("pin_hash", PinCrypto.pbkdf2(pin, salt))
            .apply()
    }

    // проверка PIN: constant-time сравнение (A7)
    fun verifyPin(pin: String): Boolean {
        val salt = pinSalt() ?: return false
        val hash = pinHash() ?: return false
        val candidate = PinCrypto.pbkdf2(pin, salt)
        return MessageDigest.isEqual(
            candidate.encodeToByteArray(),
            hash.encodeToByteArray(),
        )
    }

    fun clearPin() = prefs().edit().remove("pin_salt").remove("pin_hash").apply()

    // ─── прочее ───

    fun notificationAsked(): Boolean = prefs().getBoolean("notif_asked", false)

    fun setNotificationAsked() = prefs().edit().putBoolean("notif_asked", true).apply()

    fun consentContacts(): Boolean = prefs().getBoolean("contacts_consent", false)

    fun setConsentContacts(value: Boolean) = prefs().edit().putBoolean("contacts_consent", value).apply()

    // AL4 (аналог Power saving / LiteMode у Telegram): выкл/при низком
    // заряде/всегда; решение — в domain.PowerSaver, гейты — UI и VM
    fun powerSaverMode(): chat.client.domain.PowerMode =
        chat.client.domain.PowerMode.parse(prefs().getString("power_saver", null))

    fun setPowerSaverMode(mode: chat.client.domain.PowerMode) =
        prefs().edit().putString("power_saver", mode.key).apply()

    // полный сброс данных аккаунта при «Выйти и удалить данные» (A4):
    // всё, кроме темы/динамики/масштаба/оформления — их выбирает владелец
    // устройства, а не аккаунт
    fun resetAccountData() {
        prefs().edit()
            .remove("folders")
            .remove("lock_enabled")
            .remove("lock_biometric")
            .remove("auto_lock_sec")
            .remove("pin_fails")
            .remove("pin_locked_until")
            .remove("pin_salt")
            .remove("pin_hash")
            .remove("notif_asked")
            .remove("contacts_consent")
            .remove("secure_flag")
            .remove("hide_notif_text")
            .remove("legacy_since")
            // данные прошлого аккаунта не должны перетекать новому:
            // автобэкап, прогресс записи, просмотренные опросы и т.п.
            .remove("autobackup_pw")
            .remove("autobackup_tree")
            .remove("voice_progress")
            .remove("video_resume")
            .remove("poll_viewed")
            .remove("folders_rev")
            .remove("inactivity_months")
            .apply()
    }

    // при обычном выходе (без удаления данных): настройки остаются,
    // но папки чатов старого аккаунта новому не нужны (A4)
    fun clearFoldersOnLogout(login: String?) {
        if (login != null) return
        prefs().edit().remove("folders").apply()
    }


    // ─── AI5 (аналог резервного копирования Telegram): автобэкап ───

    // период автобэкапа, дней: 0 — выключено, 1 — ежедневно, 7 — еженедельно
    fun autoBackupMode(): Int = prefs().getInt("autobackup_mode", chat.client.domain.AutoBackup.MODE_OFF)

    fun setAutoBackupMode(days: Int) =
        prefs().edit().putInt("autobackup_mode", days).apply()

    // SAF-папка копий (persistable tree URI), null — не выбрана
    fun autoBackupTreeUri(): String? = prefs().getString("autobackup_tree", null)

    fun setAutoBackupTreeUri(uri: String?) =
        prefs().edit().putString("autobackup_tree", uri).apply()

    // пароль копий, запечатан Keystore-ключом (SecretBox): без файла
    // устройства не расшифровывается, восстановление — только по памяти
    fun autoBackupPassword(): String? =
        SecretBox.open(context, prefs().getString("autobackup_pw", null))?.decodeToString()

    // A5: автобэкап по умолчанию БЕЗ секрета идентичности (файл может уйти
    // в облако SAF). Включение — явное согласие пользователя.
    fun autoBackupIncludeIdentity(): Boolean =
        prefs().getBoolean("autobackup_identity", false)

    fun setAutoBackupIncludeIdentity(value: Boolean) =
        prefs().edit().putBoolean("autobackup_identity", value).apply()

    fun setAutoBackupPassword(pw: String?) {
        if (pw == null) {
            prefs().edit().remove("autobackup_pw").apply()
        } else {
            prefs().edit()
                .putString("autobackup_pw", SecretBox.seal(context, pw.encodeToByteArray()))
                .apply()
        }
    }

    // последний успешный прогон: 0 — не было
    fun autoBackupLastAt(): Long = prefs().getLong("autobackup_last", 0L)

    fun setAutoBackupLastAt(at: Long) = prefs().edit().putLong("autobackup_last", at).apply()

    // последняя ошибка; пусто — всё хорошо
    fun autoBackupLastError(): String = prefs().getString("autobackup_error", "") ?: ""

    fun setAutoBackupLastError(err: String?) =
        prefs().edit().putString("autobackup_error", err ?: "").apply()
}

// ЕДИНСТВЕННЫЙ PBKDF2 приложения (A7/B3): PIN и бэкап используют одну
// реализацию с 600k итераций, как требует OWASP для SHA-256.
// Своя реализация на Mac: провайдер PBKDF2WithHmacSHA256 есть только
// с API 26, а minSdk 24 — на Android 7.x PIN ронял процесс
object PinCrypto {
    private const val ITERATIONS = 600_000
    private val b64 = java.util.Base64.getEncoder()
    private val decoder = java.util.Base64.getDecoder()

    fun newSalt(): String {
        val salt = ByteArray(16)
        java.security.SecureRandom().nextBytes(salt)
        return b64.encodeToString(salt)
    }

    fun pbkdf2(pin: String, saltB64: String): String {
        val salt = decoder.decode(saltB64)
        val key = chat.client.domain.Pbkdf2.derive(
            pin.encodeToByteArray(), salt, ITERATIONS, 256 / 8,
        )
        return b64.encodeToString(key)
    }
}
