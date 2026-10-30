package chat.client.data

import android.content.Context
import chat.client.domain.MediaInfo
import chat.client.domain.Validators
import org.json.JSONObject

// Аватары чатов (Фаза U1, аналог фото профиля у Telegram): расшифрованные
// блобы — в общем медиа-кэше, здесь только дескрипторы (opaque id+key).
// Свой аватар и аватары пиров — по логину, аватар группы — по chatId.
// Сетевой путь: ava-конверт TYPE_MEDIA, в журнал не пишется.
class AvatarsStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("avatars", Context.MODE_PRIVATE)

    // дескриптор в JSON: id/key/size/w/h
    private fun encode(info: MediaInfo): String = JSONObject()
        .put("id", info.id)
        .put("key", android.util.Base64.encodeToString(info.key, android.util.Base64.NO_WRAP))
        .put("size", info.size)
        .put("w", info.width)
        .put("h", info.height)
        .toString()

    private fun decode(raw: String?): MediaInfo? {
        if (raw == null) return null
        return try {
            val json = JSONObject(raw)
            val id = json.optString("id")
            if (id.isEmpty() || !Regex("^[0-9a-zA-Z_-]{1,128}$").matches(id)) return null
            val key = try {
                android.util.Base64.decode(json.optString("key", ""), android.util.Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (key.size != 32) return null
            MediaInfo(
                id = id, key = key, size = json.optLong("size", 0),
                width = json.optInt("w", 0), height = json.optInt("h", 0),
                thumbB64 = "",
            )
        } catch (_: Exception) {
            null
        }
    }

    // аватар пользователя по логину (свой — по своему логину)
    @Synchronized
    fun userOf(login: String): MediaInfo? {
        if (!Validators.validLogin(login)) return null
        return decode(prefs().getString("u_$login", null))
    }

    @Synchronized
    fun setUser(login: String, info: MediaInfo?) {
        if (!Validators.validLogin(login)) return
        val editor = prefs().edit()
        if (info == null) editor.remove("u_$login") else editor.putString("u_$login", encode(info))
        editor.apply()
    }

    // аватар группы по chatId
    @Synchronized
    fun groupOf(chatId: String): MediaInfo? {
        if (!chatId.startsWith("grp_")) return null
        return decode(prefs().getString("g_$chatId", null))
    }

    @Synchronized
    fun setGroup(chatId: String, info: MediaInfo?) {
        if (!chatId.startsWith("grp_")) return
        val editor = prefs().edit()
        if (info == null) {
            editor.remove("g_$chatId")
            // снятие чистит и эмодзи: аватар у группы один (Фаза AE7)
            editor.remove("ge_$chatId")
        } else {
            // фото перекрывает эмодзи: одновременно не живём
            editor.putString("g_$chatId", encode(info)).remove("ge_$chatId")
        }
        editor.apply()
    }

    // Фаза AE7 (аналог эмодзи-аватара у Telegram): аватар-эмодзи группы;
    // фото при установке снимается — аватар один, как у Telegram
    @Synchronized
    fun groupEmoji(chatId: String): String? {
        if (!chatId.startsWith("grp_")) return null
        return prefs().getString("ge_$chatId", null)
    }

    @Synchronized
    fun setGroupEmoji(chatId: String, emoji: String?) {
        if (!chatId.startsWith("grp_")) return
        val editor = prefs().edit()
        if (emoji == null) {
            editor.remove("ge_$chatId")
        } else {
            editor.putString("ge_$chatId", emoji).remove("g_$chatId")
        }
        editor.apply()
    }

    // очистка осиротевшего аватара группы (удаление чата)
    @Synchronized
    fun cleanup(chatId: String) {
        if (chatId.startsWith("grp_")) prefs().edit().remove("g_$chatId").remove("ge_$chatId").apply()
    }

    // Фаза AE1: id блобов всех известных аватаров — их зачистка
    // по сроку хранения не ест (аватар не «стареет»)
    @Synchronized
    fun avatarIds(): Set<String> =
        prefs().all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith("u_") && !key.startsWith("g_")) return@mapNotNull null
            decode(value.toString())?.id?.takeIf { it.isNotEmpty() }
        }.toSet()

    // полный сброс (wipe-наборы)
    @Synchronized
    fun wipe() {
        prefs().edit().clear().apply()
    }
}
