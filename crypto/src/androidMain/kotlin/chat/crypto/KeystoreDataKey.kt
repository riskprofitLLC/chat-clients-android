package chat.crypto

import java.io.File
import java.util.concurrent.ConcurrentHashMap

// 4.2: единая обёртка «ключ данных под AndroidKeyStore» с кэшем в памяти.
// Прежде пять копий (Media, Stickers, GifFavorites, SecretBox,
// AndroidMessageStore) жили сами по себе, а Media.load и Stickers.load
// разворачивали Keystore-ключ на КАЖДЫЙ вызов. Кэш привязан к метке
// файла: подмена/порча ключевого файла инвалидирует запись — разворот
// падает, как и раньше. Сброс при wipe — reset()
object KeystoreDataKey {

    private class Cached(val key: ByteArray, val stamp: Long)

    // путь ключевого файла → развёрнутый ключ
    private val cache = ConcurrentHashMap<String, Cached>()

    @Synchronized
    fun reset() {
        cache.clear()
    }

    // метка файла: длина+время ловят подмену/порчу даже в ту же миллисекунду
    private fun stampOf(file: File): Long = file.lastModified() * 31 + file.length()

    // ключ данных каталога: есть — разворачиваем, нет — генерируем.
    // Возвращает РАЗВЁРНУТЫЙ ключ; исключение — Keystore недоступен
    @Synchronized
    fun dataKey(dir: File, fileName: String = "vault.key"): ByteArray {
        val keyFile = File(dir, fileName)
        val path = keyFile.absolutePath
        val stamp = if (keyFile.exists()) stampOf(keyFile) else -1L
        cache[path]?.let { if (stamp >= 0 && it.stamp == stamp) return it.key }
        val key = if (keyFile.exists()) {
            KeyVaultAndroid.unwrap(keyFile.readBytes())
        } else {
            dir.mkdirs()
            val fresh = CryptoPrimitives.randomBytes(32)
            writeAtomic(keyFile, KeyVaultAndroid.wrap(fresh))
            fresh
        }
        cache[path] = Cached(key, stampOf(keyFile))
        return key
    }

    // атомарная запись обёртки: tmp + fsync + rename. Обрыв посреди
    // первой записи не оставит битый vault.key, из-за которого всё
    // хранилище не открыть (как в журнале, K1)
    private fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                throw java.io.IOException("vault.key: атомарная замена не удалась")
            }
        } finally {
            tmp.delete()
        }
    }
}
