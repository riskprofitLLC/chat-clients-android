package chat.client.data

import android.content.Context

// Локальный список заблокированных отправителей (A3): блобы таких
// отправителей сбрасываются при дрейне, доставка подтверждается (у
// отправителя не копятся ретраи). Полезен против спама приглашениями.
class BlockedSendersStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("blocked_senders", Context.MODE_PRIVATE)

    @Synchronized
    fun isBlocked(login: String): Boolean = prefs().getStringSet(KEY, emptySet())?.contains(login) == true

    @Synchronized
    fun block(login: String) {
        val current = prefs().getStringSet(KEY, emptySet()) ?: emptySet()
        // новый set: StringSet из prefs нельзя мутировать на месте
        prefs().edit().putStringSet(KEY, current + login).apply()
    }

    @Synchronized
    fun unblock(login: String) {
        val current = prefs().getStringSet(KEY, emptySet()) ?: emptySet()
        prefs().edit().putStringSet(KEY, current - login).apply()
    }

    @Synchronized
    fun all(): Set<String> = prefs().getStringSet(KEY, emptySet()) ?: emptySet()

    private companion object {
        const val KEY = "blocked"
    }
}
