package chat.client.data

import android.content.Context
import chat.client.domain.StoryFormat
import chat.client.domain.Validators
import org.json.JSONArray
import org.json.JSONObject

// GE4: локальный список историй (своих и входящих) для ленты. Как и
// расшифрованная история, живёт только на устройстве; просроченные
// чистятся при чтении. Ключ — "from/mediaId".
class StoryStore(private val context: Context) {

    class Story(
        val from: String,
        val mediaId: String,
        val exp: Long,
        val ts: Long,
        val mine: Boolean,
        val caption: String,
        val thumbB64: String,
    )

    private fun prefs() = context.getSharedPreferences("stories", Context.MODE_PRIVATE)

    @Synchronized
    fun add(from: String, mediaId: String, exp: Long, ts: Long, mine: Boolean, caption: String, thumbB64: String) {
        if (!Validators.validLogin(from) && from != "grp") return
        if (mediaId.isEmpty()) return
        val key = "$from/$mediaId"
        // старая запись с тем же ключом заменяется: пересобираем массив
        val arr = JSONArray()
        val old = loadRaw()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optString("k") != key) arr.put(o)
        }
        arr.put(
            JSONObject()
                .put("k", key)
                .put("f", from)
                .put("m", mediaId)
                .put("e", exp)
                .put("t", ts)
                .put("mine", mine)
                .put("c", caption.take(200))
                .put("th", thumbB64.take(131_072)),
        )
        prefs().edit().putString(KEY, arr.toString()).apply()
    }

    // все неистёкшие, свежие сверху
    @Synchronized
    fun all(now: Long): List<Story> = loadRaw().let { arr ->
        val out = ArrayList<Story>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val exp = o.optLong("e", 0L)
            if (StoryFormat.isExpired(exp, now)) continue
            out += Story(
                o.optString("f"), o.optString("m"), exp, o.optLong("t", 0L),
                o.optBoolean("mine", false), o.optString("c"), o.optString("th"),
            )
        }
        out.sortByDescending { it.ts }
        out
    }

    // уборка просроченных (вызывается при чтении и при получении новой)
    @Synchronized
    fun purge(now: Long) {
        val arr = loadRaw()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!StoryFormat.isExpired(o.optLong("e", 0L), now)) kept.put(o)
        }
        prefs().edit().putString(KEY, kept.toString()).apply()
    }

    @Synchronized
    fun wipe() = prefs().edit().clear().apply()

    private fun loadRaw(): JSONArray = try {
        JSONArray(prefs().getString(KEY, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    companion object {
        const val KEY = "list"
    }
}
