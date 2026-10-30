package chat.client.domain

import org.json.JSONArray
import org.json.JSONObject

// Синхронизация папок чатов между СВОИМИ устройствами (Фаза AJ, аналог
// синка папок у Telegram): конверт TYPE_FOLDERS {"v":ревизия,"f":[…]}.
// Ревизия — монотонный счётчик правок: применяется только более новый
// набор. Уходит только на свои устройства; в журнал не пишется.
object FolderSyncFormat {

    const val MAX_FOLDERS = ChatFolders.MAX_FOLDERS
    const val MAX_NAME = ChatFolders.NAME_LIMIT

    class Decoded(val rev: Long, val folders: List<ChatFolder>)

    fun encode(rev: Long, folders: List<ChatFolder>): ByteArray =
        Envelope.encode(
            Envelope.TYPE_FOLDERS,
            JSONObject()
                .put("v", rev)
                .put("f", JSONArray(folders.map { folderJson(it) }))
                .toString(),
        )

    // строгий разбор: null — не синк папок; мусор молча отбрасывается
    fun decode(payload: ByteArray): Decoded? {
        return try {
            val json = JSONObject(payload.decodeToString())
            val rev = json.optLong("v", 0L)
            val arr = json.optJSONArray("f")
            if (rev <= 0L || arr == null || arr.length() == 0 || arr.length() > MAX_FOLDERS) {
                null
            } else {
                val out = ArrayList<ChatFolder>(arr.length())
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: return null
                    val id = f.optString("id")
                    val name = f.optString("n")
                    if (id.length !in 1..32 || name.length !in 1..MAX_NAME) return null
                    out += ChatFolder(
                        id = id,
                        name = name,
                        include = stringSet(f.optJSONArray("i")),
                        exclude = stringSet(f.optJSONArray("e")),
                        showUnread = f.optInt("u", 0) == 1,
                        showGroups = f.optInt("g", 0) == 1,
                        showPrivate = f.optInt("p", 0) == 1,
                        icon = f.optString("ic").take(16),
                    )
                }
                Decoded(rev, out)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun of(content: ByteArray): Decoded? {
        val envelope = Envelope.decode(content) ?: return null
        if (envelope.type != Envelope.TYPE_FOLDERS) return null
        return decode(envelope.payload)
    }

    private fun folderJson(f: ChatFolder): JSONObject = JSONObject()
        .put("id", f.id)
        .put("n", f.name)
        .put("i", JSONArray(f.include))
        .put("e", JSONArray(f.exclude))
        .put("u", if (f.showUnread) 1 else 0)
        .put("g", if (f.showGroups) 1 else 0)
        .put("p", if (f.showPrivate) 1 else 0)
        .put("ic", f.icon)

    private fun stringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out += s
        }
        return out
    }
}
