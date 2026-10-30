package chat.client.data

import android.content.Context

// Непрочитанные: lastReadTime per chatId + ручная отметка «непрочитано»
// (Фаза O3, по образцу Telegram unread_mark). Класс вместо глобального объекта.
class UnreadStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("unread", Context.MODE_PRIVATE)

    fun markRead(chatId: String) {
        prefs().edit()
            .putLong(chatId, System.currentTimeMillis())
            // открытие чата снимает и ручную отметку (Фаза O3)
            .remove(markKey(chatId))
            // и счётчик непрочитанных упоминаний (Фаза AE4)
            .remove(mentionKey(chatId))
            .apply()
    }

    fun lastRead(chatId: String): Long = prefs().getLong(chatId, 0L)

    // ручная отметка «непрочитано»: точка-бейдж у чата без счётчика
    fun markUnread(chatId: String) {
        prefs().edit().putBoolean(markKey(chatId), true).apply()
    }

    fun isMarkedUnread(chatId: String): Boolean = prefs().getBoolean(markKey(chatId), false)

    fun clearMark(chatId: String) {
        prefs().edit().remove(markKey(chatId)).apply()
    }

    // Фаза AE4 (аналог @-бейджа у Telegram): счётчик непрочитанных
    // @упоминаний; растёт при дрейне, снимается открытием чата
    fun addMentions(chatId: String, count: Int) {
        if (count <= 0) return
        prefs().edit().putInt(mentionKey(chatId), mentionCount(chatId) + count).apply()
    }

    fun mentionCount(chatId: String): Int = prefs().getInt(mentionKey(chatId), 0)

    fun cleanup(chatId: String) =
        prefs().edit().remove(chatId).remove(markKey(chatId)).remove(mentionKey(chatId)).apply()

    private fun markKey(chatId: String) = "mark_$chatId"

    private fun mentionKey(chatId: String) = "men_$chatId"
}
