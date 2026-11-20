package chat.client.data

import android.content.Context
import chat.client.domain.BioFormat
import chat.client.domain.Validators

// «О себе» (Фаза W2, аналог bio у Telegram): строка профиля по логину.
// Сетевой путь — событие TYPE_BIO (в журнал не пишется); хранение —
// свёрнутое, как у аватаров (U1): текст мал, блобов нет.
class BiosStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("bios", Context.MODE_PRIVATE)

    @Synchronized
    fun of(login: String): String? {
        if (!Validators.validLogin(login)) return null
        return prefs().getString("u_$login", null)?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun set(login: String, bio: String?) {
        if (!Validators.validLogin(login)) return
        val editor = prefs().edit()
        val sanitized = BioFormat.sanitize(bio ?: "")
        if (sanitized == null) editor.remove("u_$login") else editor.putString("u_$login", sanitized)
        editor.apply()
    }

    // GE6: бизнес-часы профиля по логину (сырой JSON дней из поля "bh" bio)
    @Synchronized
    fun hoursOf(login: String): String? {
        if (!Validators.validLogin(login)) return null
        return prefs().getString("h_$login", null)?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun setHours(login: String, hoursJson: String?) {
        if (!Validators.validLogin(login)) return
        val editor = prefs().edit()
        if (hoursJson.isNullOrEmpty()) editor.remove("h_$login") else editor.putString("h_$login", hoursJson)
        editor.apply()
    }

    // полный сброс (wipe-наборы)
    @Synchronized
    fun wipe() {
        prefs().edit().clear().apply()
    }
}
