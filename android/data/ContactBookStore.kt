package chat.client.data

import android.content.Context
import org.json.JSONObject

// Связки адресной книги телефона с e-mail и логинами чата (локально,
// SharedPreferences): contactKey (телефон) → email, email → login.
// Сырые адреса на сервер не уходят — только SHA-256 хэши (см. discover).
class ContactBookStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("contact_book", Context.MODE_PRIVATE)

    // кэш в памяти: JSON парсится один раз при старте, не на каждый вызов
    @Volatile private var contactEmailCache: MutableMap<String, String>? = null
    @Volatile private var emailLoginCache: MutableMap<String, String>? = null
    @Volatile private var loginEmailCache: MutableMap<String, String>? = null

    private fun loadMap(key: String, cache: () -> MutableMap<String, String>?,
                        setCache: (MutableMap<String, String>) -> Unit): MutableMap<String, String> {
        cache()?.let { return it }
        val raw = prefs().getString(key, null) ?: return mutableMapOf<String, String>().also { setCache(it) }
        val map = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(raw)
            for (k in obj.keys()) map[k] = obj.getString(k)
        } catch (_: Exception) {
        }
        setCache(map)
        return map
    }

    private fun saveMap(key: String, map: Map<String, String>,
                        setCache: (MutableMap<String, String>) -> Unit) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs().edit().putString(key, obj.toString()).apply()
        setCache(map.toMutableMap())
        if (key == "email_login") loginEmailCache = null // обратный индекс устарел
    }

    private fun norm(email: String) = email.trim().lowercase()

    // ─── contactKey (телефон) → email, ручной ввод пользователем ───

    fun emailForContact(contactKey: String): String? =
        loadMap("contact_email", { contactEmailCache }, { contactEmailCache = it })[contactKey]

    fun setEmailForContact(contactKey: String, email: String) {
        val map = loadMap("contact_email", { contactEmailCache }, { contactEmailCache = it })
        val e = norm(email)
        if (e.isEmpty() || !e.contains("@")) {
            map.remove(contactKey)
        } else {
            map[contactKey] = e
        }
        saveMap("contact_email", map, { contactEmailCache = it })
    }

    fun allContactEmails(): Map<String, String> =
        loadMap("contact_email", { contactEmailCache }, { contactEmailCache = it })

    // ─── email → login (результат discover: кто нашёлся в чате) ───

    fun loginForEmail(email: String): String? =
        loadMap("email_login", { emailLoginCache }, { emailLoginCache = it })[norm(email)]

    fun setLoginForEmail(email: String, login: String) {
        val map = loadMap("email_login", { emailLoginCache }, { emailLoginCache = it })
        map[norm(email)] = login
        saveMap("email_login", map, { emailLoginCache = it })
    }

    // обратный индекс логин → email (для автоподтягивания в чате), строится один раз
    fun emailForLogin(login: String): String? {
        loginEmailCache?.let { return it[login] }
        val map = loadMap("email_login", { emailLoginCache }, { emailLoginCache = it })
        val reverse = mutableMapOf<String, String>()
        for ((e, l) in map) reverse[l] = e
        loginEmailCache = reverse
        return reverse[login]
    }

    fun allEmailLogins(): Map<String, String> =
        loadMap("email_login", { emailLoginCache }, { emailLoginCache = it })
}
