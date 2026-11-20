package chat.client.data

import android.content.Context
import chat.client.domain.Tofu

// Запомненные ключи устройств собеседников (TOFU) + метка «код сверен».
// Сравнение — чистая функция domain.Tofu.verdict.
//
// A8: ключи разнесены по префиксам — k/<peer>/<deviceId> для дайджестов и
// v/<peer> для «сверен». Прежние ключи "<peer>/<id>" и "verified_<peer>"
// конфликтовали: accept пира с логином вида verified_<чужой> стирал чужой
// флаг сверки, а ключи пира и флаги могли задевать друг друга при
// substring-сопоставлении.
class KnownKeysStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("known_keys", Context.MODE_PRIVATE)

    private fun keyPrefix(peer: String) = "k/$peer/"
    private fun verifiedKey(peer: String) = "v/$peer"

    init {
        migrateLegacyKeys()
    }

    // A8: разовая миграция старого формата "<peer>/<id>" и "verified_<peer>"
    // на разнесённые префиксы; запускается при первом создании стора
    @Synchronized
    private fun migrateLegacyKeys() {
        if (prefs().getBoolean("migrated_v2", false)) return
        val editor = prefs().edit()
        for ((entry, value) in prefs().all) {
            val key = entry as? String ?: continue
            val slash = key.indexOf('/')
            when {
                // старый дайджест: "<peer>/<deviceId>"
                slash > 0 && !key.startsWith("verified_") && value is String -> {
                    editor.putString("k/${key.substring(0, slash)}/${key.substring(slash + 1)}", value)
                    editor.remove(key)
                }
                // старый флаг сверки: "verified_<peer>"
                key.startsWith("verified_") && value is Boolean -> {
                    editor.putBoolean("v/${key.removePrefix("verified_")}", value)
                    editor.remove(key)
                }
            }
        }
        editor.putBoolean("migrated_v2", true).apply()
    }

    // deviceId → digest hex для пира
    fun known(peer: String): Map<String, String> {
        val prefix = keyPrefix(peer)
        val out = HashMap<String, String>()
        for ((key, value) in prefs().all) {
            if (key is String && key.startsWith(prefix) && value is String) {
                out[key.removePrefix(prefix)] = value
            }
        }
        return out
    }

    fun verdict(peer: String, current: List<Pair<String, ByteArray>>): Tofu.Verdict =
        Tofu.verdict(known(peer), current)

    // запомнить текущий состав ключей (первое знакомство или подтверждение).
    // A8: смена ключей сбрасывает флаг «код сверен» — сверка обязательна заново
    fun accept(peer: String, devices: List<Pair<String, ByteArray>>) {
        val previous = known(peer)
        val editor = prefs().edit()
        val live = devices.map { it.first }.toSet()
        // исчезнувшие устройства больше не отслеживаем
        for (entry in prefs().all) {
            val key = entry.key as? String ?: continue
            if (key.startsWith(keyPrefix(peer))) {
                val id = key.removePrefix(keyPrefix(peer))
                if (id !in live) editor.remove(key)
            }
        }
        // A2: смена состава устройств (новое или подмена ключа) обнуляет сверку
        val reverify = Tofu.requiresReverify(previous, devices)
        for ((deviceId, pub) in devices) {
            val digest = Tofu.digestOf(pub)
            editor.putString(keyPrefix(peer) + deviceId, digest)
        }
        if (reverify) {
            editor.putBoolean(verifiedKey(peer), false)
        }
        editor.apply()
    }

    // ─── состояние «код сверен» ───

    fun isVerified(peer: String): Boolean = prefs().getBoolean(verifiedKey(peer), false)

    fun setVerified(peer: String, value: Boolean) =
        prefs().edit().putBoolean(verifiedKey(peer), value).apply()

    fun cleanup(peer: String) {
        val prefix = keyPrefix(peer)
        val editor = prefs().edit()
        for (key in prefs().all.keys) {
            if (key.startsWith(prefix)) editor.remove(key)
        }
        editor.remove(verifiedKey(peer)).apply()
    }
}
