package chat.client.data

import android.content.Context
import chat.client.domain.TranslateFormat
import org.json.JSONObject

// GE3: локальный кэш переводов {"ref|lang": {"t": текст, "ts": время}}.
// Хранится только на устройстве, потолок записей, вытеснение старых.
class TranslateStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("translations", Context.MODE_PRIVATE)

    @Synchronized
    fun get(ref: String, lang: String): String? {
        val o = load().optJSONObject(TranslateFormat.cacheKey(ref, lang)) ?: return null
        return o.optString("t").takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun put(ref: String, lang: String, text: String) {
        val all = load()
        all.put(
            TranslateFormat.cacheKey(ref, lang),
            JSONObject().put("t", text.take(TranslateFormat.MAX_TEXT)).put("ts", System.currentTimeMillis()),
        )
        // вытеснение: при переполнении выкидываем самые старые
        if (all.length() > 500) {
            val keys = ArrayList<String>()
            val it = all.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sortBy { k -> all.optJSONObject(k)?.optLong("ts", 0L) ?: 0L }
            for (k in keys.take(all.length() - 500)) all.remove(k)
        }
        prefs().edit().putString(KEY, all.toString()).apply()
    }

    @Synchronized
    fun wipe() = prefs().edit().clear().apply()

    private fun load(): JSONObject = try {
        JSONObject(prefs().getString(KEY, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    companion object {
        const val KEY = "map"
    }
}
