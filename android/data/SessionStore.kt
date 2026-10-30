package chat.client.data

import android.content.Context
import chat.crypto.KeyVaultAndroid
import java.io.File
import java.util.Base64

// Шифрование маленьких секретов (токен, логин, email) AES-GCM ключом,
// обёрнутым AndroidKeyStore. Формат значения: base64(IV 12Б || ct||tag).
object SecretBox {

    private fun keyFile(context: Context) = File(context.filesDir, "vault/prefsvault.key")

    // A8: ключ кэшируется в памяти — разворот Keystore на каждый seal/open
    // (каждое чтение токена, каждый persist outbox) был лишней работой.
    // Кэш привязан к метке файла: порча/подмена vault (тест A6, кража
    // образа) инвалидирует кэш — разворот падает, как и раньше
    private class CachedKey(val key: ByteArray, val stamp: Long) {
        companion object {
            // длина+время: подмена/порча файла ловится даже в ту же мс
            fun stampOf(file: File): Long = file.lastModified() * 31 + file.length()
        }
    }

    @Volatile private var cached: CachedKey? = null

    // сброс кэша: vault стёрт (wipe) — следующий вызов развернёт новый ключ.
    // 4.2: сброс и общего кэша KeystoreDataKey
    @Synchronized
    fun reset() {
        cached = null
        chat.crypto.KeystoreDataKey.reset()
    }

    // ключ шифрования префов: один раз генерируем, храним в Keystore-обёртке.
    // 4.2: создание/разворот — через единый KeystoreDataKey, свой кэш
    // (с инвалидацией по метке vault) остаётся: чтение токена частое
    @Synchronized
    private fun dataKey(context: Context): ByteArray {
        val file = keyFile(context)
        val stamp = if (file.exists()) CachedKey.stampOf(file) else -1L
        cached?.let { if (it.stamp == stamp && stamp >= 0) return it.key }
        val key = chat.crypto.KeystoreDataKey.dataKey(file.parentFile!!, file.name)
        cached = CachedKey(key, CachedKey.stampOf(keyFile(context)))
        return key
    }

    fun seal(context: Context, plain: ByteArray): String {
        val iv = chat.crypto.CryptoPrimitives.randomBytes(12)
        val ct = chat.crypto.CryptoPrimitives.aesGcmEncrypt(dataKey(context), iv, plain, ByteArray(0))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    // null — значения нет ИЛИ оно повреждено (Keystore/Vault недоступны)
    fun open(context: Context, sealed: String?): ByteArray? {
        if (sealed == null) return null
        return try {
            val blob = Base64.getDecoder().decode(sealed)
            if (blob.size < 12 + 16) return null
            chat.crypto.CryptoPrimitives.aesGcmDecrypt(
                dataKey(context),
                blob.copyOfRange(0, 12),
                blob.copyOfRange(12, blob.size),
                ByteArray(0),
            )
        } catch (_: Exception) {
            null
        }
    }

    // работает ли Keystore-хранилище префов вообще
    fun vaultHealthy(context: Context): Boolean = try {
        val probe = seal(context, "probe".encodeToByteArray())
        open(context, probe) != null
    } catch (_: Exception) {
        false
    }
}

// Состояние сессии в SharedPreferences: токен/логин/email — шифрованные
// (SecretBox), публичный ключ — открытый (он и так публикуется на сервере).
// Идентичность (секретный ключ) — в filesDir/identity, см. Session.
object SessionStore {

    private const val PREFS = "session"
    private const val KEY_TOKEN = "token"
    private const val KEY_LOGIN = "login"
    private const val KEY_EMAIL = "email"
    private const val KEY_PUB = "pub"
    private const val KEY_DEVICE = "device_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun identityDir(context: Context) = File(context.filesDir, "identity")

    private fun secretFile(context: Context) = File(identityDir(context), "secret.bin")

    // уникальный id устройства: генерируется при первой установке и переживает
    // выход (иначе сервер не узнает устройство при повторном входе)
    fun deviceId(context: Context): String {
        val existing = prefs(context).getString(KEY_DEVICE, null)
        if (existing != null) return existing
        val raw = java.security.SecureRandom().let { rnd ->
            ByteArray(6).also { rnd.nextBytes(it) }
        }
        val id = "d" + raw.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    // восстановление device_id из бэкапа (A11): сервер знает pub этого id
    fun saveDeviceId(context: Context, deviceId: String) {
        prefs(context).edit().putString(KEY_DEVICE, deviceId).apply()
    }

    fun saveToken(context: Context, token: String) {
        prefs(context).edit()
            .putString(KEY_TOKEN, SecretBox.seal(context, token.encodeToByteArray())).apply()
    }

    fun saveLogin(context: Context, login: String) {
        prefs(context).edit()
            .putString(KEY_LOGIN, SecretBox.seal(context, login.encodeToByteArray())).apply()
    }

    fun saveEmail(context: Context, email: String) {
        prefs(context).edit()
            .putString(KEY_EMAIL, SecretBox.seal(context, email.encodeToByteArray())).apply()
    }

    fun savePub(context: Context, pubB64: String) {
        prefs(context).edit().putString(KEY_PUB, pubB64).apply()
    }

    // атомарное сохранение идентичности: временный файл + rename ПОВЕРХ
    // существующего (1.3). Прежнее delete+rename оставляло окно: обрыв
    // между ними уничтожал секретный ключ безвозвратно; rename(2) на
    // Linux/Android атомарно замещает цель
    fun saveIdentity(context: Context, secret: ByteArray, pubB64: String) {
        val dir = identityDir(context).apply { mkdirs() }
        val tmp = File(dir, "secret.tmp")
        tmp.writeBytes(KeyVaultAndroid.wrap(secret))
        if (!tmp.renameTo(secretFile(context))) {
            throw java.io.IOException("не удалось сохранить ключ идентичности")
        }
        savePub(context, pubB64)
    }

    // секрет идентичности: null — файла нет; исключение — Keystore недоступен.
    // 1.3: если обрыв убил secret.bin, но остался secret.tmp — восстанавливаем
    fun loadSecret(context: Context): ByteArray? {
        val file = secretFile(context)
        if (!file.exists()) {
            val tmp = File(identityDir(context), "secret.tmp")
            if (!tmp.exists()) return null
            if (!tmp.renameTo(file)) return null
        }
        return KeyVaultAndroid.unwrap(file.readBytes())
    }

    fun token(context: Context): String? =
        SecretBox.open(context, prefs(context).getString(KEY_TOKEN, null))?.decodeToString()

    fun login(context: Context): String? =
        SecretBox.open(context, prefs(context).getString(KEY_LOGIN, null))?.decodeToString()

    fun email(context: Context): String? =
        SecretBox.open(context, prefs(context).getString(KEY_EMAIL, null))?.decodeToString()

    fun pubB64(context: Context): String? = prefs(context).getString(KEY_PUB, null)

    fun hasIdentityFile(context: Context): Boolean = secretFile(context).exists() &&
        !pubB64(context).isNullOrEmpty()

    // выход: чистим сессию, ключи и история остаются на устройстве
    fun clearSession(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_LOGIN).remove(KEY_EMAIL).apply()
    }

    // полное стирание: сессия, ключи, история, медиа, настройки чатов,
    // WAL дрейна, общие файлы и ВСЁ из app_prefs кроме темы (A4/A6) —
    // старый аккаунт не оставляет следов (папки, PIN, согласия).
    // 3.6: список хранилищ — StorageManifest, единый источник правды
    fun wipeAll(context: Context) {
        clearSession(context)
        // A8: ключ префов кэширован — vault стирается, кэш обязан умереть
        SecretBox.reset()
        StorageManifest.wipeFilesDirs(context)
        StorageManifest.wipeCache(context)
        StorageManifest.wipePrefs(context)
        // сброс настроек приложения, кроме темы/оформления (A4):
        // папки со списками id чужих чатов, PIN-блокировка, согласия
        AppPrefs(context).resetAccountData()
        // расписание автобэкапа гасим вместе с аккаунтом
        chat.client.service.AutoBackupWorker.cancel(context)
        // сброс идентификатора устройства: старое связывание более недействительно
        prefs(context).edit().clear().apply()
    }

    // стирание только идентичности и истории (восстановление из бэкапа).
    // 3.6: тот же StorageManifest — без него после restore оставались
    // хвосты прошлого аккаунта (обои, livegeo, кэш камеры)
    fun wipeIdentityAndData(context: Context) {
        StorageManifest.wipeFilesDirs(context)
        StorageManifest.wipeCache(context)
        StorageManifest.wipePrefs(context)
        clearSession(context)
    }
}
