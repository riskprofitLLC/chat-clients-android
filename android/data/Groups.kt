package chat.client.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

// Группа: имя + участники (логины, включая владельца) + ревизия состава.
// Определение живёт в SharedPreferences, раздаётся внутри самих сообщений
// (GroupFormat), сервер о группах не знает. Все операции синхронизованы:
// параллельные записи из дрейна и UI не теряют обновлений (A24).
// Ревизия (A3): rev = known.rev + 1 на каждое локальное изменение имени
// или состава; из конвертов состав применяется только при info.rev > known.rev
typealias GroupChat = chat.client.domain.GroupChat

class GroupStore(private val context: Context) {

    // неподтверждённое изменение состава (A9): добавления участников
    // применяются только после явного «Принять». rev — ревизия отправителя.
    // AC3/AC4/AD6: вместе с составом едет и остальное состояние конверта —
    // режим канала/описание/медленный режим/запрет пересылки, чтобы
    // принятие не теряло их
    class PendingChange(
        val sender: String,
        val members: List<String>,
        val rev: Long,
        val broadcast: String? = null,
        val desc: String = "",
        val slowMode: Int = 0,
        val noForward: Boolean = false,
        val admins: List<String> = emptyList(),
        val noReactions: Boolean = false,
    )

    private fun prefs() = context.getSharedPreferences("groups", Context.MODE_PRIVATE)

    // A11: кэш разбора в памяти — byId дёргается в циклах сводок, и
    // прежний парсинг JSON на каждый вызов занимал заметную долю списка
    @Volatile private var allCache: List<GroupChat>? = null

    // локализованное имя по умолчанию (B11): без хардкода «Группа»
    private fun defaultName(): String = context.getString(chat.client.R.string.default_group_name)

    @Synchronized
    fun all(): List<GroupChat> {
        // A11: повторные чтения — из кэша
        allCache?.let { return it }
        val parsed: List<GroupChat> = try {
            val raw = prefs().getString("groups", null) ?: "[]"
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val g = arr.getJSONObject(i)
                val members = g.getJSONArray("members")
                GroupChat(
                    id = g.getString("id"),
                    name = g.getString("name"),
                    members = List(members.length()) { members.getString(it) },
                    rev = g.optLong("rev", 0L),
                    // Фаза AB10: режим канала — владелец; пустое — обычная
                    broadcast = g.optString("bc").ifEmpty { null },
                    // Фаза AC3: описание группы; Фаза AC4: медленный режим
                    desc = g.optString("gd"),
                    slowMode = chat.client.domain.SlowMode.sanitize(g.optInt("sm", 0)),
                    // Фаза AD6: запрет пересылки/сохранения
                    noForward = g.optInt("nf", 0) == 1,
                    // AI3: администраторы
                    admins = stringList(g, "ad"),
                    // Фаза AJ: запрет реакций
                    noReactions = g.optInt("nr", 0) == 1,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        allCache = parsed
        return parsed
    }

    fun byId(id: String): GroupChat? = all().firstOrNull { it.id == id }

    fun exists(id: String): Boolean = byId(id) != null

    // создать группу; id — криптослучайный, подходит под chatId [A-Za-z0-9_-]{1,64}
    @Synchronized
    fun create(name: String, members: List<String>): GroupChat {
        val group = GroupChat(
            newGroupId(),
            name.trim().take(64).ifEmpty { defaultName() },
            members.distinct(),
            rev = 1L, // первая ревизия: конверты без v (0) её не перетрут (A3)
        )
        save(group)
        return group
    }

    // сохранить как есть (rev — ответственность вызывающего) (A3)
    @Synchronized
    fun save(group: GroupChat) {
        val list = all().filter { it.id != group.id } + group
        writeAll(list)
    }

    // переименование — локальное изменение: rev+1 (A3)
    @Synchronized
    fun rename(id: String, name: String) {
        byId(id)?.let {
            save(it.copy(name = name.trim().take(64).ifEmpty { it.name }, rev = chat.client.domain.GroupRules.nextRev(it.rev)))
        }
    }

    // Фаза AB10: режим канала — локальное изменение rev+1, как rename;
    // владелец — включивший (логин), null — выключение (только владелец)
    @Synchronized
    fun setBroadcast(id: String, owner: String?) {
        byId(id)?.let { save(it.copy(broadcast = owner, rev = chat.client.domain.GroupRules.nextRev(it.rev))) }
    }

    // Фаза AC3: описание группы — локальное изменение rev+1, как rename;
    // любой участник (у нас нет админов, как у переименования N3)
    @Synchronized
    fun setDescription(id: String, desc: String) {
        byId(id)?.let {
            save(it.copy(desc = chat.client.domain.GroupRules.sanitizeDesc(desc), rev = chat.client.domain.GroupRules.nextRev(it.rev)))
        }
    }

    // Фаза AC4: медленный режим — локальное изменение rev+1, как rename
    @Synchronized
    fun setSlowMode(id: String, periodSec: Int) {
        byId(id)?.let {
            save(it.copy(slowMode = chat.client.domain.SlowMode.sanitize(periodSec), rev = chat.client.domain.GroupRules.nextRev(it.rev)))
        }
    }

    // изменение состава по решению владельца (добавить/исключить): rev+1 (A3)
    @Synchronized
    fun changeMembers(id: String, members: List<String>) {
        byId(id)?.let { save(it.copy(members = members.distinct(), rev = chat.client.domain.GroupRules.nextRev(it.rev))) }
    }

    // Фаза AD6 (аналог «Restrict saving content»): запрет пересылки —
    // локальное изменение rev+1, как rename; включает/выключает любой участник
    @Synchronized
    fun setNoForward(id: String, on: Boolean) {
        byId(id)?.let { save(it.copy(noForward = on, rev = chat.client.domain.GroupRules.nextRev(it.rev))) }
    }

    // Фаза AJ (аналог Allowed Reactions: none): запрет реакций —
    // локальное изменение rev+1, как запрет пересылки; любой участник
    @Synchronized
    fun setNoReactions(id: String, on: Boolean) {
        byId(id)?.let { save(it.copy(noReactions = on, rev = chat.client.domain.GroupRules.nextRev(it.rev))) }
    }

    // AI3 (аналог admin rights у Telegram): список администраторов —
    // локальное изменение rev+1, как переименование; права админа —
    // удаление чужих сообщений; назначение открыто любому участнику
    // (как остальные настройки — наша эгалитарная модель групп)
    @Synchronized
    fun setAdmins(id: String, admins: List<String>) {
        byId(id)?.let {
            save(
                it.copy(
                    admins = chat.client.domain.GroupRules.sanitizeAdmins(admins, it.members),
                    rev = chat.client.domain.GroupRules.nextRev(it.rev),
                ),
            )
        }
    }

    private fun stringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    @Synchronized
    fun delete(id: String) {
        writeAll(all().filter { it.id != id })
        clearPendingChange(id)
    }

    // ─── покинутые группы (A4): локальный block-list, чтобы участники
    // не воскрешали чат новыми конвертами и приглашениями ───

    @Synchronized
    fun markLeft(id: String) {
        val left = leftIds() + id
        prefs().edit().putStringSet("left_ids", left).apply()
    }

    @Synchronized
    fun clearLeft(id: String) {
        prefs().edit().putStringSet("left_ids", leftIds() - id).apply()
    }

    @Synchronized
    fun isLeft(id: String): Boolean = id in leftIds()

    private fun leftIds(): Set<String> =
        prefs().getStringSet("left_ids", emptySet()) ?: emptySet()

    // ─── отложенные изменения состава (A9) ───

    @Synchronized
    fun setPendingChange(
        chatId: String,
        sender: String,
        members: List<String>,
        rev: Long,
        broadcast: String? = null,
        desc: String = "",
        slowMode: Int = 0,
        noForward: Boolean = false,
        admins: List<String> = emptyList(),
        noReactions: Boolean = false,
    ) {
        val json = JSONObject()
            .put("sender", sender)
            .put("members", JSONArray(members))
            .put("rev", rev)
        broadcast?.let { json.put("bc", it) }
        if (desc.isNotEmpty()) json.put("gd", desc)
        if (slowMode > 0) json.put("sm", slowMode)
        if (noForward) json.put("nf", 1)
        if (admins.isNotEmpty()) json.put("ad", JSONArray(admins))
        if (noReactions) json.put("nr", 1)
        prefs().edit().putString("pending_$chatId", json.toString()).apply()
    }

    @Synchronized
    fun pendingChange(chatId: String): PendingChange? {
        val raw = prefs().getString("pending_$chatId", null) ?: return null
        return try {
            val json = JSONObject(raw)
            val members = json.getJSONArray("members")
            PendingChange(
                json.getString("sender"),
                List(members.length()) { members.getString(it) },
                json.optLong("rev", 0L),
                json.optString("bc").ifEmpty { null },
                json.optString("gd"),
                chat.client.domain.SlowMode.sanitize(json.optInt("sm", 0)),
                json.optInt("nf", 0) == 1,
                stringList(json, "ad"),
                json.optInt("nr", 0) == 1,
            )
        } catch (_: Exception) {
            null
        }
    }

    // подтвердить изменение: применяем состав с ревизией ОТПРАВИТЕЛЯ (A3) —
    // наш known.rev выравнивается с его, последующие его конверты не «старее»;
    // имя — из известного, остальное состояние — из конверта отправителя
    @Synchronized
    fun acceptPendingChange(chatId: String): GroupChat? {
        val pending = pendingChange(chatId) ?: return null
        val known = byId(chatId)
        val group = GroupChat(
            chatId, known?.name ?: defaultName(), pending.members.distinct(), pending.rev,
            pending.broadcast, pending.desc, pending.slowMode, pending.noForward,
            chat.client.domain.GroupRules.sanitizeAdmins(pending.admins, pending.members),
            pending.noReactions,
        )
        save(group)
        clearPendingChange(chatId)
        return group
    }

    // ─── приглашения в группы (C4): неизвестная группа НЕ создаётся ───
    // автоматически — сначала явное решение пользователя. Лимит ≤20 (A3):
    // старейшие вытесняются, спам не переполняет список

    class GroupInvite(
        val chatId: String,
        val sender: String,
        val name: String,
        val members: List<String>,
        val rev: Long,
        val broadcast: String? = null,
        val desc: String = "",
        val slowMode: Int = 0,
        val noForward: Boolean = false,
        val admins: List<String> = emptyList(),
        val noReactions: Boolean = false,
    )

    @Synchronized
    fun setInvite(
        chatId: String,
        sender: String,
        name: String,
        members: List<String>,
        rev: Long,
        broadcast: String? = null,
        desc: String = "",
        slowMode: Int = 0,
        noForward: Boolean = false,
        admins: List<String> = emptyList(),
        noReactions: Boolean = false,
    ) {
        val json = JSONObject()
            .put("sender", sender)
            .put("name", name)
            .put("members", JSONArray(members))
            .put("rev", rev)
            .put("at", System.currentTimeMillis())
        broadcast?.let { json.put("bc", it) }
        if (desc.isNotEmpty()) json.put("gd", desc)
        if (slowMode > 0) json.put("sm", slowMode)
        if (noForward) json.put("nf", 1)
        if (admins.isNotEmpty()) json.put("ad", JSONArray(admins))
        if (noReactions) json.put("nr", 1)
        prefs().edit().putString("invite_$chatId", json.toString()).apply()
        evictOldInvites()
    }

    // вытеснение старейших приглашений за пределами лимита (A3)
    private fun evictOldInvites() {
        val keys = prefs().all.keys.filter { it.startsWith("invite_") }
        if (keys.size <= MAX_INVITES) return
        val withTime = keys.mapNotNull { key ->
            val at = try {
                JSONObject(prefs().all[key].toString()).optLong("at", 0L)
            } catch (_: Exception) {
                0L
            }
            Triple(key, at, prefs().all[key].toString())
        }
        val toDrop = withTime.sortedBy { it.second }.take(withTime.size - MAX_INVITES)
        val editor = prefs().edit()
        for ((key, _, _) in toDrop) editor.remove(key)
        editor.apply()
    }

    @Synchronized
    fun invite(chatId: String): GroupInvite? {
        val raw = prefs().getString("invite_$chatId", null) ?: return null
        return try {
            val json = JSONObject(raw)
            val members = json.getJSONArray("members")
            GroupInvite(
                chatId,
                json.getString("sender"),
                json.optString("name", defaultName()),
                List(members.length()) { members.getString(it) },
                json.optLong("rev", 0L),
                json.optString("bc").ifEmpty { null },
                json.optString("gd"),
                chat.client.domain.SlowMode.sanitize(json.optInt("sm", 0)),
                json.optInt("nf", 0) == 1,
                stringList(json, "ad"),
                json.optInt("nr", 0) == 1,
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun allInvites(): List<GroupInvite> {
        val out = ArrayList<GroupInvite>()
        for (key in prefs().all.keys) {
            if (key.startsWith("invite_")) key.removePrefix("invite_").let { invite(it)?.let { i -> out += i } }
        }
        return out
    }

    @Synchronized
    fun clearInvite(chatId: String) {
        prefs().edit().remove("invite_$chatId").apply()
    }

    @Synchronized
    fun clearPendingChange(chatId: String) {
        prefs().edit().remove("pending_$chatId").apply()
    }

    // восстановление определений групп после импорта/переноса (A17):
    // состав и ревизия — из ПОСЛЕДНЕГО конверта по ts (B8): первый мог
    // быть устаревшим составом самой группы
    @Synchronized
    fun restoreFromHistory(store: StoreGate) {
        val known = all().associateBy { it.id }
        for (chatId in store.chatIds()) {
            if (known.containsKey(chatId)) continue
            val latest = store.history(chatId, Int.MAX_VALUE)
                .sortedBy { it.timestamp }
                .lastOrNull { record ->
                    chat.client.domain.MessageCodec.groupOf(record.content, allowLegacy = true)
                        ?.info?.id == chatId
                } ?: continue
            chat.client.domain.MessageCodec.groupOf(latest.content, allowLegacy = true)?.let {
                save(
                    GroupChat(
                        it.info.id, it.info.name, it.info.members, it.info.rev,
                        it.info.broadcast, it.info.desc, it.info.slowMode,
                        it.info.noForward, it.info.admins, it.info.noReactions,
                    ),
                )
            }
        }
    }

    private fun writeAll(list: List<GroupChat>) {
        val arr = JSONArray()
        for (g in list) {
            val json = JSONObject()
                .put("id", g.id)
                .put("name", g.name)
                .put("members", JSONArray(g.members))
                .put("rev", g.rev)
            // Фаза AB10: владелец канала; null — обычная группа
            g.broadcast?.let { json.put("bc", it) }
            // Фаза AC3/AC4: описание и медленный режим — как имя/канал
            if (g.desc.isNotEmpty()) json.put("gd", g.desc)
            if (g.slowMode > 0) json.put("sm", g.slowMode)
            // Фаза AD6: запрет пересылки/сохранения
            if (g.noForward) json.put("nf", 1)
            // AI3: администраторы — могут удалять чужие сообщения
            if (g.admins.isNotEmpty()) json.put("ad", JSONArray(g.admins))
            // Фаза AJ: запрет реакций
            if (g.noReactions) json.put("nr", 1)
            arr.put(json)
        }
        prefs().edit().putString("groups", arr.toString()).apply()
        // A11: кэш рвётся при записи — следующий all() перечитает
        allCache = null
    }

    companion object {
        const val MAX_INVITES = 20 // лимит приглашений (A3)
    }
}

// сгенерированный групповой id: криптослучайный
fun newGroupId(): String {
    val raw = ByteArray(8).also { SecureRandom().nextBytes(it) }
    return "grp_" + raw.joinToString("") { "%02x".format(it) }
}
