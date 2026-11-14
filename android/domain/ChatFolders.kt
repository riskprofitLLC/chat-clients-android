package chat.client.domain

import org.json.JSONArray
import org.json.JSONObject

// Папка чатов (по образцу Telegram): имя, фильтры-правила и явные списки
// включённых/исключённых чатов. Чистая логика — сериализация и матчинг
// покрываются JVM-тестами.
data class ChatFolder(
    val id: String,
    val name: String,
    val include: Set<String>,
    val exclude: Set<String>,
    val showUnread: Boolean,
    val showGroups: Boolean,
    val showPrivate: Boolean,
    // Фаза AD3 (аналог иконок папок у Telegram): эмодзи-иконка
    // таба; пустая строка — без иконки
    val icon: String = "",
)

// Хранение и правила папок. «Избранное» (чат с собой) попадает в папку
// только явным включением — фильтры на него не действуют.
object ChatFolders {

    const val MAX_FOLDERS = 10
    const val NAME_LIMIT = 24

    // Фаза AD3: кап длины иконки — один эмодзи-кластер (с запасом
    // на ZWJ-цепочки и тона кожи); длинный хвост молча режется
    private const val ICON_LIMIT = 16

    // иконка папки: первый эмодзи-кластер; мусор — пустая строка
    fun sanitizeIcon(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        val cluster = EmojiOnly.firstCluster(s)
        return if (cluster.length <= ICON_LIMIT) cluster else ""
    }

    fun newId(): String = "f" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    fun validName(name: String): Boolean = name.isNotBlank() && name.length <= NAME_LIMIT

    // папка обязана что-то матчить: хоть один фильтр или явный список
    fun isUsable(folder: ChatFolder): Boolean =
        folder.include.isNotEmpty() || folder.showUnread || folder.showGroups || folder.showPrivate

    // порядок папок (аналог reorder у Telegram): сдвиг на соседнюю
    // позицию, границы — no-op; порядок табов = порядок списка
    fun move(list: List<ChatFolder>, id: String, up: Boolean): List<ChatFolder> {
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return list
        val target = if (up) index - 1 else index + 1
        if (target !in list.indices) return list
        val out = list.toMutableList()
        val item = out.removeAt(index)
        out.add(target, item)
        return out
    }

    fun toJson(list: List<ChatFolder>): String {
        val arr = JSONArray()
        for (f in list) {
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("n", f.name)
                    .put("inc", JSONArray(f.include))
                    .put("exc", JSONArray(f.exclude))
                    .put("u", f.showUnread)
                    .put("g", f.showGroups)
                    .put("p", f.showPrivate)
                    .put("ic", f.icon),
            )
        }
        return arr.toString()
    }

    // мягкий разбор: битые элементы пропускаем, целиком битый JSON — пустой список
    fun parse(raw: String?): List<ChatFolder> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<ChatFolder>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("n")
                if (id.isEmpty() || name.isEmpty()) continue
                out += ChatFolder(
                    id = id,
                    name = name,
                    include = stringSet(o.optJSONArray("inc")),
                    exclude = stringSet(o.optJSONArray("exc")),
                    showUnread = o.optBoolean("u", false),
                    showGroups = o.optBoolean("g", false),
                    showPrivate = o.optBoolean("p", false),
                    // старые записи без ic — без иконки (миграция мягкая)
                    icon = sanitizeIcon(o.optString("ic")),
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun stringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    // чат в папке: не исключён и (явно включён ИЛИ сработал фильтр)
    fun matches(
        folder: ChatFolder,
        chatId: String,
        isGroup: Boolean,
        isSelf: Boolean,
        unread: Int,
    ): Boolean {
        if (chatId in folder.exclude) return false
        if (isSelf) return chatId in folder.include
        val byFilter = (folder.showUnread && unread > 0) ||
            (folder.showGroups && isGroup) ||
            (folder.showPrivate && !isGroup)
        return chatId in folder.include || byFilter
    }
}
