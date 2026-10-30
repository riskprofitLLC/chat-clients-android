package chat.client.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Режим темы интерфейса. AH2: AUTO — тёмная тема по расписанию
// (окно из AppPrefs.autoDarkFrom/autoDarkTo, аналог авто-ночного
// режима Telegram)
enum class ThemeMode { SYSTEM, LIGHT, DARK, AUTO }

// Per-chat настройки: пин, мьют, архив, автоудаление, время доставки (✓✓),
// закреплённое сообщение. Черновик ответа хранится JSON — сниппет
// с «|» больше не ломает ref (A20).
class ChatPrefsStore(private val context: Context) {

    companion object {
        // Фаза AA2 (аналог Mentions у Telegram): режим уведомлений группы
        const val NOTIFY_ALL = 0
        const val NOTIFY_MENTIONS = 1
        // Фаза AB1: потолок просмотренных одноразовых медиа на чат
        private const val VIEW_ONCE_MAX = 256
    }

    private fun prefs() = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    @Synchronized
    fun pinned(chatId: String): Boolean = prefs().getBoolean("pin_$chatId", false)

    // закрепление чата: вместе с флагом ведётся ручной порядок закреплённых
    // (Фаза AG10) — новый закреп уходит в конец, откреплённый выпадает
    @Synchronized
    fun setPinned(chatId: String, value: Boolean) {
        val editor = prefs().edit().putBoolean("pin_$chatId", value)
        val order = pinOrder()
        val updated = if (value) {
            chat.client.domain.PinOrder.pin(order, chatId)
        } else {
            chat.client.domain.PinOrder.unpin(order, chatId)
        }
        if (updated != order) {
            editor.putString("pin_order", chat.client.domain.PinOrder.encode(updated))
        }
        editor.apply()
    }

    // Фаза AG10 (аналог reorder закреплённых у Telegram): ручной порядок
    // закреплённых чатов — JSON-массив id в порядке показа
    @Synchronized
    fun pinOrder(): List<String> =
        chat.client.domain.PinOrder.parse(prefs().getString("pin_order", null))

    @Synchronized
    fun setPinOrder(list: List<String>) {
        prefs().edit().putString("pin_order", chat.client.domain.PinOrder.encode(list)).apply()
    }

    // мьют на срок (Фаза M3): until > сейчас — без звука; MAX_VALUE — навсегда.
    // Миграция: старый boolean-мьют читается как «навсегда»
    @Synchronized
    fun mutedUntil(chatId: String): Long {
        val until = prefs().getLong("muteuntil_$chatId", 0L)
        if (until > 0L) return until
        return if (prefs().getBoolean("mute_$chatId", false)) Long.MAX_VALUE else 0L
    }

    @Synchronized
    fun setMutedUntil(chatId: String, until: Long) {
        prefs().edit()
            .putLong("muteuntil_$chatId", until)
            .remove("mute_$chatId")
            .apply()
    }

    @Synchronized
    fun muted(chatId: String): Boolean = mutedUntil(chatId) > System.currentTimeMillis()

    @Synchronized
    fun setMuted(chatId: String, value: Boolean) =
        setMutedUntil(chatId, if (value) Long.MAX_VALUE else 0L)

    // архив: чат скрыт из основного списка (по образцу Telegram)
    @Synchronized
    fun archived(chatId: String): Boolean = prefs().getBoolean("arch_$chatId", false)

    @Synchronized
    fun setArchived(chatId: String, value: Boolean) =
        prefs().edit().putBoolean("arch_$chatId", value).apply()

    // AH4 (аналог Hidden chats у Telegram): чат спрятан за PIN — не
    // виден в списке и поиске, уведомления без имени и текста.
    // В отличие от архива — приватность, а не порядок
    @Synchronized
    fun hidden(chatId: String): Boolean = prefs().getBoolean("hidden_$chatId", false)

    // AK10: запрет скриншотов на чат — FOLLOW/OFF/ON поверх глобальной
    fun secureOverride(chatId: String): Int =
        chat.client.domain.ChatSecurity.normalize(prefs().getInt("secure_$chatId", chat.client.domain.ChatSecurity.FOLLOW))

    @Synchronized
    fun setSecureOverride(chatId: String, value: Int) =
        prefs().edit().putInt("secure_$chatId", chat.client.domain.ChatSecurity.normalize(value)).apply()

    // AK4: содержание уведомлений на чат — FOLLOW/SHOW/HIDE поверх
    // глобального «скрывать текст уведомлений»
    fun contentOverride(chatId: String): Int =
        chat.client.domain.ChatNotificationContent.normalize(
            prefs().getInt("notif_content_$chatId", chat.client.domain.ChatNotificationContent.FOLLOW),
        )

    @Synchronized
    fun setContentOverride(chatId: String, value: Int) =
        prefs().edit().putInt("notif_content_$chatId", chat.client.domain.ChatNotificationContent.normalize(value)).apply()

    @Synchronized
    fun setHidden(chatId: String, value: Boolean) =
        prefs().edit().putBoolean("hidden_$chatId", value).apply()

    // автоудаление чата по умолчанию, секунды; 0 — выключено
    @Synchronized
    fun autoDeleteSec(chatId: String): Long = prefs().getLong("autodel_$chatId", 0L)

    @Synchronized
    fun setAutoDeleteSec(chatId: String, sec: Long) =
        prefs().edit().putLong("autodel_$chatId", sec).apply()

    // время доставки моих сообщений собеседником (✓✓), мс
    @Synchronized
    fun deliveredAt(chatId: String): Long = prefs().getLong("delivered_$chatId", 0L)

    @Synchronized
    fun setDeliveredAt(chatId: String, at: Long) {
        val current = deliveredAt(chatId)
        if (at > current) prefs().edit().putLong("delivered_$chatId", at).apply()
    }

    // время прочтения моих сообщений собеседником (синие ✓✓), мс
    @Synchronized
    fun readAt(chatId: String): Long = prefs().getLong("read_$chatId", 0L)

    @Synchronized
    fun setReadAt(chatId: String, at: Long) {
        val current = readAt(chatId)
        if (at > current) prefs().edit().putLong("read_$chatId", at).apply()
    }

    // участники группы, доставившие (для ✓✓ в группах): member → время
    // доставки (A9). Несравнимо с ts сообщения: ✓ только если участник
    // забрал очередь ПОСЛЕ отправки этого сообщения
    @Synchronized
    fun groupDeliveredAt(chatId: String): Map<String, Long> {
        val raw = prefs().getString("gdeliveredat_$chatId", null)
        if (raw != null) {
            return try {
                val json = JSONObject(raw)
                val out = HashMap<String, Long>()
                for (key in json.keys()) out[key] = json.optLong(key, 0L)
                out
            } catch (_: Exception) {
                emptyMap()
            }
        }
        // миграция старого набора без времён (A9): считаем доставленным
        // на момент миграции — исторические ✓ не теряются
        val legacy = prefs().getStringSet("gdelivered_$chatId", emptySet()) ?: emptySet()
        if (legacy.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val migrated = legacy.associateWith { now }
        setGroupDeliveredAt(chatId, migrated)
        prefs().edit().remove("gdelivered_$chatId").apply()
        return migrated
    }

    @Synchronized
    fun setGroupDeliveredAt(chatId: String, delivered: Map<String, Long>) {
        val json = JSONObject()
        for ((member, at) in delivered) json.put(member, at)
        prefs().edit().putString("gdeliveredat_$chatId", json.toString()).apply()
    }

    // кадр delivered: участник забрал очередь в момент at (A9)
    @Synchronized
    fun setGroupMemberDeliveredAt(chatId: String, member: String, at: Long) {
        val current = groupDeliveredAt(chatId).toMutableMap()
        if ((current[member] ?: 0L) < at) {
            current[member] = at
            setGroupDeliveredAt(chatId, current)
        }
    }

    // Фаза AG3: чат уже приходил — дорогая проверка истории делается
    // один раз, и известные чаты не уезжают в архив при включении настройки
    @Synchronized
    fun chatKnown(chatId: String): Boolean = prefs().getBoolean("known_$chatId", false)

    @Synchronized
    fun setChatKnown(chatId: String, value: Boolean) =
        prefs().edit().putBoolean("known_$chatId", value).apply()

    // локальное имя собеседника: видимо только на этом устройстве
    @Synchronized
    fun name(chatId: String): String? = prefs().getString("name_$chatId", null)

    @Synchronized
    fun setName(chatId: String, value: String?) {
        val name = chat.client.domain.ContactName.sanitize(value ?: "")
        if (name == null) prefs().edit().remove("name_$chatId").apply()
        else prefs().edit().putString("name_$chatId", name).apply()
    }

    // черновик текста на чат: не теряется при переключении.
    // savedAt — момент записи: синхронизация с устройства-родня (AA8)
    // применяет только более свежий черновик
    @Synchronized
    fun draft(chatId: String): String = prefs().getString("draft_$chatId", "") ?: ""

    @Synchronized
    fun draftSavedAt(chatId: String): Long = prefs().getLong("draft_at_$chatId", 0L)

    @Synchronized
    fun setDraft(chatId: String, text: String) {
        val editor = prefs().edit()
        if (text.isBlank()) editor.remove("draft_$chatId")
        else editor.putString("draft_$chatId", text.take(4096))
        editor.putLong("draft_at_$chatId", System.currentTimeMillis()).apply()
    }

    // AA8: приём черновика с другого своего устройства — только новее
    @Synchronized
    fun setDraftSynced(chatId: String, text: String, at: Long): Boolean {
        if (at <= draftSavedAt(chatId)) return false
        val editor = prefs().edit()
        if (text.isBlank()) editor.remove("draft_$chatId")
        else editor.putString("draft_$chatId", text.take(4096))
        editor.putLong("draft_at_$chatId", at).apply()
        return true
    }

    // цитата ответа в черновике: JSON {a,q,i}; старый формат «a|q|i» читаем
    @Synchronized
    fun draftReply(chatId: String): String? = prefs().getString("draft_reply_$chatId", null)

    @Synchronized
    fun setDraftReply(chatId: String, value: String?) {
        if (value == null) prefs().edit().remove("draft_reply_$chatId").apply()
        else prefs().edit().putString("draft_reply_$chatId", value.take(600)).apply()
    }

    // закреплённые сообщения чата (Фаза W5): список [{i,to}], последний —
    // в конце; потолок — PinnedMessages.MAX. Миграция старого единичного
    // закрепа "pinmsg_" — в список из одного
    @Synchronized
    fun pinnedMessages(chatId: String): List<Pair<String, Long>> {
        val list = chat.client.domain.PinnedMessages.decode(
            prefs().getString("pinmsgs_$chatId", null),
        )
        if (list.isNotEmpty()) return list
        // миграция единичного закрепа старого формата (Фаза I)
        val legacy = prefs().getString("pinmsg_$chatId", null) ?: return emptyList()
        val single = chat.client.domain.PinnedMessages.fromSingle(legacy) ?: return emptyList()
        setPinnedMessages(chatId, listOf(single))
        prefs().edit().remove("pinmsg_$chatId").apply()
        return listOf(single)
    }

    @Synchronized
    fun setPinnedMessages(chatId: String, list: List<Pair<String, Long>>) {
        val editor = prefs().edit()
        if (list.isEmpty()) {
            editor.remove("pinmsgs_$chatId").remove("pinmsg_$chatId")
        } else {
            editor.putString("pinmsgs_$chatId", chat.client.domain.PinnedMessages.encode(list))
        }
        editor.apply()
    }

    // добавить/убрать один закреп (событие TYPE_PIN p=1|0)
    @Synchronized
    fun addPinnedMessage(chatId: String, ref: String, ts: Long) =
        setPinnedMessages(chatId, chat.client.domain.PinnedMessages.add(pinnedMessages(chatId), ref, ts))

    @Synchronized
    fun removePinnedMessage(chatId: String, ref: String) =
        setPinnedMessages(chatId, chat.client.domain.PinnedMessages.remove(pinnedMessages(chatId), ref))

    // обои конкретного чата (Фаза T2): -1 — как в настройках,
    // 0 — узор, 1 — однотонный; мусор читается как FOLLOW
    @Synchronized
    fun chatWallpaper(chatId: String): Int =
        chat.client.domain.WallpaperChoice.normalize(prefs().getInt("wp_$chatId", -1))

    @Synchronized
    fun setChatWallpaper(chatId: String, value: Int) {
        val norm = chat.client.domain.WallpaperChoice.normalize(value)
        if (norm == chat.client.domain.WallpaperChoice.FOLLOW) {
            prefs().edit().remove("wp_$chatId").apply()
        } else {
            prefs().edit().putInt("wp_$chatId", norm).apply()
        }
    }

    // AL-раунд (аналог Background dimming у Telegram): затемнение своих
    // фото-обоев чата, %; дефолт — прежние фиксированные 45
    @Synchronized
    fun wallDim(chatId: String): Int =
        chat.client.domain.WallpaperDim.sanitize(prefs().getInt("wp_dim_$chatId", chat.client.domain.WallpaperDim.DEFAULT))

    @Synchronized
    fun setWallDim(chatId: String, percent: Int) {
        val dim = chat.client.domain.WallpaperDim.sanitize(percent)
        if (dim == chat.client.domain.WallpaperDim.DEFAULT) {
            prefs().edit().remove("wp_dim_$chatId").apply()
        } else {
            prefs().edit().putInt("wp_dim_$chatId", dim).apply()
        }
    }

    // Фаза Y3 (аналог «Save to Gallery» у Telegram): автосохранение
    // входящих фото/видео этого чата в системную галерею. Локальная
    // настройка — как мьют/архив, по сети не едет
    fun autoSaveToGallery(chatId: String): Boolean =
        prefs().getBoolean("autosave_$chatId", false)

    fun setAutoSaveToGallery(chatId: String, value: Boolean) =
        prefs().edit().putBoolean("autosave_$chatId", value).apply()

    // Фаза AA2: режим уведомлений группы — все сообщения или только @упоминания
    @Synchronized
    fun notifyMode(chatId: String): Int = prefs().getInt("nmode_$chatId", NOTIFY_ALL)
    @Synchronized
    fun setNotifyMode(chatId: String, mode: Int) {
        prefs().edit().putInt("nmode_$chatId", mode.coerceIn(NOTIFY_ALL, NOTIFY_MENTIONS)).apply()
    }

    // Фаза AB1: просмотренные одноразовые медиа чата — JSON-массив id,
    // старые вытесняются при переполнении
    @Synchronized
    fun viewOnceConsumed(chatId: String, mediaId: String): Boolean {
        val raw = prefs().getString("vo_$chatId", null) ?: return false
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) if (arr.optString(i) == mediaId) return true
            false
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun markViewOnceConsumed(chatId: String, mediaId: String) {
        val ids = ArrayList<String>()
        try {
            val raw = prefs().getString("vo_$chatId", null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotEmpty()) ids += id
                }
            }
        } catch (_: Exception) {
            ids.clear()
        }
        if (mediaId in ids) return
        ids += mediaId
        while (ids.size > VIEW_ONCE_MAX) ids.removeAt(0)
        prefs().edit().putString("vo_$chatId", JSONArray(ids).toString()).apply()
    }

    // Фаза AB4: персональный тон уведомлений чата; null — как в настройках
    @Synchronized
    fun notifySound(chatId: String): String? {
        val raw = prefs().getString("nsnd_$chatId", null)
        return if (chat.client.domain.NotificationSound.isValidKey(raw)) raw else null
    }

    @Synchronized
    fun setNotifySound(chatId: String, key: String?) {
        if (!chat.client.domain.NotificationSound.isValidKey(key)) return
        if (key == null) {
            prefs().edit().remove("nsnd_$chatId").apply()
            return
        }
        prefs().edit().putString("nsnd_$chatId", key).apply()
    }

    // очистка осиротевших настроек при удалении чата
    @Synchronized
    fun cleanup(chatId: String) {
        // удалённый чат выпадает и из ручного порядка закреплённых (Фаза AG10)
        val order = pinOrder()
        val pruned = chat.client.domain.PinOrder.unpin(order, chatId)
        val editor = prefs().edit()
            .remove("pin_$chatId").remove("mute_$chatId").remove("muteuntil_$chatId")
            .remove("autodel_$chatId")
            .remove("delivered_$chatId").remove("read_$chatId").remove("gdelivered_$chatId")
            .remove("gdeliveredat_$chatId")
            .remove("draft_$chatId").remove("draft_reply_$chatId").remove("pinmsg_$chatId")
            .remove("draft_at_$chatId")
            .remove("pinmsgs_$chatId")
            .remove("arch_$chatId").remove("name_$chatId").remove("wp_$chatId")
            .remove("known_$chatId")
            .remove("nsnd_$chatId")
            .remove("autosave_$chatId").remove("nmode_$chatId")
            .remove("vo_$chatId")
            .remove("hidden_$chatId")
        if (pruned != order) {
            editor.putString("pin_order", chat.client.domain.PinOrder.encode(pruned))
        }
        editor.apply()
    }
}
