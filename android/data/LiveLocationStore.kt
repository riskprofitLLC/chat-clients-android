package chat.client.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

// Живая геопозиция (Фаза AA7): активные трансляции по чатам —
// chatId → until (мс). Кэш в памяти, на диск — JSON разом; UI читает
// StateFlow для плашки «осталось N мин».
class LiveLocationStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("livegeo", Context.MODE_PRIVATE)

    private val cache = HashMap<String, Long>() // chatId → дедлайн
    private val sentAt = HashMap<String, Long>() // chatId → последняя отправка

    private val _active = MutableStateFlow<Map<String, Long>>(emptyMap())
    val active: StateFlow<Map<String, Long>> = _active.asStateFlow()

    init {
        load()
        publish()
    }

    @Synchronized
    private fun load() {
        cache.clear()
        sentAt.clear()
        val raw = prefs.getString("live", null) ?: return
        try {
            val json = JSONObject(raw)
            for (key in json.keys()) cache[key] = json.getLong(key)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun persist() {
        val json = JSONObject()
        for ((k, v) in cache) json.put(k, v)
        // core-ktx в зависимостях нет — платформенный edit() честнее сапресса
        @android.annotation.SuppressLint("UseKtx")
        val editor = prefs.edit()
        editor.putString("live", json.toString()).apply()
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        _active.value = cache.filterValues { it > now }
    }

    @Synchronized
    fun start(chatId: String, until: Long) {
        cache[chatId] = until
        sentAt.remove(chatId)
        persist()
    }

    @Synchronized
    fun stop(chatId: String) {
        cache.remove(chatId)
        sentAt.remove(chatId)
        persist()
    }

    @Synchronized
    fun until(chatId: String): Long = cache[chatId] ?: 0L

    @Synchronized
    fun markSent(chatId: String, at: Long) {
        sentAt[chatId] = at
    }

    @Synchronized
    fun lastSent(chatId: String): Long = sentAt[chatId] ?: 0L

    // истёкшие трансляции снимаются и не возвращаются
    @Synchronized
    fun expireDue(now: Long): List<String> {
        val due = cache.filterValues { it <= now }.keys.toList()
        if (due.isNotEmpty()) {
            due.forEach {
                cache.remove(it)
                sentAt.remove(it)
            }
            persist()
        }
        return due
    }
}
