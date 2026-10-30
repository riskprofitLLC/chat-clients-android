package chat.client.data

import android.content.Context
import org.json.JSONObject

// Таймеры самоудаления сообщений. Кэш в памяти, на диск — JSON разом;
// планировщик берёт ближайший дедлайн без перечитывания префов.
// Удаление ТОЛЬКО локальное: у собеседника копия остаётся.
class SelfDestructStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("destruct", Context.MODE_PRIVATE)
    private val cache = HashMap<String, Long>() // «chatId|ts» → дедлайн

    // A10: новый таймер будит цикл немедленно — раньше «10 секунд» могло
    // сработать с опозданием до 30 с из-за длинного сна тикера
    private val _wake = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val wake: kotlinx.coroutines.flow.SharedFlow<Unit> = _wake

    init {
        load()
    }

    @Synchronized
    private fun load() {
        cache.clear()
        val raw = prefs.getString("timers", null) ?: return
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
        prefs.edit().putString("timers", json.toString()).apply()
    }

    fun key(chatId: String, ts: Long) = "$chatId|$ts"

    @Synchronized
    fun all(): Map<String, Long> = cache.toMap()

    @Synchronized
    fun forChat(chatId: String): Map<Long, Long> {
        val prefix = "$chatId|"
        val out = HashMap<Long, Long>()
        for ((key, deadline) in cache) {
            if (!key.startsWith(prefix)) continue
            val ts = key.removePrefix(prefix).toLongOrNull() ?: continue
            out[ts] = deadline
        }
        return out
    }

    @Synchronized
    fun deadline(chatId: String, ts: Long): Long? = cache[key(chatId, ts)]

    // deadline <= 0 — снять таймер
    @Synchronized
    fun set(chatId: String, ts: Long, deadline: Long) {
        if (deadline <= 0) cache.remove(key(chatId, ts)) else cache[key(chatId, ts)] = deadline
        persist()
        // A10: цикл пересчитывает сон — короткий таймер срабатывает вовремя
        _wake.tryEmit(Unit)
    }

    @Synchronized
    fun remove(chatId: String, ts: Long) = set(chatId, ts, 0)

    // ближайший дедлайн (мс) или null — для планировщика
    @Synchronized
    fun nextDeadline(now: Long): Long? = cache.values.filter { it > now }.minOrNull()

    // просроченные ключи
    @Synchronized
    fun due(now: Long): List<String> = cache.filter { it.value <= now }.keys.toList()

    @Synchronized
    fun cleanupChat(chatId: String) {
        val prefix = "$chatId|"
        cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        persist()
    }
}
