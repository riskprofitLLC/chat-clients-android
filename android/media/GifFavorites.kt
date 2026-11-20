package chat.client.media

import android.content.Context
import chat.client.domain.GifIndex
import chat.crypto.CryptoPrimitives
import chat.crypto.KeyVaultAndroid
import java.io.File

// Избранные GIF (Фаза AF8, аналог Saved GIFs у Telegram): байты и индекс
// лежат в filesDir/gifs ЗАШИФРОВАННЫМИ ключом из AndroidKeyStore — как
// стикеры, plaintext на диске не появляется. Индекс — домен GifIndex;
// вставка в конец, при потолке старейшие вытесняются вместе с файлами.
object GifFavorites {

    // потолок одного GIF: как у вложений, иначе не отправить повторно
    const val MAX_BYTES = chat.client.domain.GifIndex.MAX_BYTES

    private fun dir(context: Context) = File(context.filesDir, "gifs")

    // 4.2: единый KeystoreDataKey с кэшем — разворот на каждый вызов был лишней работой
    private fun dataKey(dir: File): ByteArray = chat.crypto.KeystoreDataKey.dataKey(dir)

    private fun encrypt(dir: File, plain: ByteArray): ByteArray {
        val iv = CryptoPrimitives.randomBytes(12)
        return iv + CryptoPrimitives.aesGcmEncrypt(dataKey(dir), iv, plain, ByteArray(0))
    }

    private fun decrypt(dir: File, blob: ByteArray): ByteArray? = try {
        CryptoPrimitives.aesGcmDecrypt(
            dataKey(dir),
            blob.copyOfRange(0, 12),
            blob.copyOfRange(12, blob.size),
            ByteArray(0),
        )
    } catch (_: Exception) {
        null
    }

    private fun indexFile(context: Context) = File(dir(context), "index.bin")

    private fun gifFile(context: Context, id: String) = File(dir(context), "$id.gif")

    // индекс читается при каждом обращении: набор мал, отдельный кэш
    // усложнил бы согласованность после wipe/переноса
    private fun readIndex(context: Context): MutableList<GifIndex.Item> {
        val file = indexFile(context)
        if (!file.exists()) return ArrayList()
        return GifIndex.decode(decrypt(dir(context), file.readBytes())?.decodeToString())
            .toMutableList()
    }

    @Synchronized
    private fun writeIndex(context: Context, items: List<GifIndex.Item>) {
        indexFile(context).writeBytes(
            encrypt(dir(context), GifIndex.encode(items).encodeToByteArray()),
        )
    }

    // новый GIF в избранное; null — байты не читаются или велики
    @Synchronized
    fun save(context: Context, data: ByteArray, name: String): GifIndex.Item? {
        if (data.isEmpty() || data.size > MAX_BYTES) return null
        val d = dir(context)
        d.mkdirs()
        val id = System.currentTimeMillis().toString(36) + "-" +
            CryptoPrimitives.randomBytes(4).joinToString("") { "%02x".format(it) }
        gifFile(context, id).writeBytes(encrypt(d, data))
        val item = GifIndex.Item(id, name.take(64), data.size.toLong(), System.currentTimeMillis())
        val items = readIndex(context)
        // потолок набора: старейшие вытесняются вместе с файлами
        GifIndex.upsert(items, item).also { bounded ->
            val evicted = items.filterNot { old -> bounded.any { it.id == old.id } }
            evicted.forEach { gifFile(context, it.id).delete() }
            writeIndex(context, bounded)
        }
        return item
    }

    fun list(context: Context): List<GifIndex.Item> = try {
        readIndex(context)
    } catch (_: Exception) {
        emptyList()
    }

    // байты GIF для отправки/превью; null — запись утрачена
    fun load(context: Context, id: String): ByteArray? {
        val file = gifFile(context, id)
        if (!file.exists()) return null
        return decrypt(dir(context), file.readBytes())
    }

    // удаление из избранного; битые записи индекса вычищаются попутно
    @Synchronized
    fun delete(context: Context, id: String) {
        gifFile(context, id).delete()
        writeIndex(context, GifIndex.remove(readIndex(context), id))
    }

    // полный сброс (wipe): используется при удалении данных аккаунта
    @Synchronized
    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    // суммарный размер набора
    fun sizeBytes(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile && it.name != "vault.key" }?.sumOf { it.length() } ?: 0L
}
