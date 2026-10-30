package chat.client.media

import android.content.Context
import chat.crypto.CryptoPrimitives
import chat.crypto.KeyVaultAndroid
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Локальный набор стикеров (Фаза P3, аналог recent/custom stickers в
// Telegram): байты стикеров и индекс лежат в filesDir/stickers ЗАШИФРОВАННЫМИ
// ключом из AndroidKeyStore — как медиа, plaintext на диске не появляется.
// Индекс — один файл; сами стикеры — по файлу на запись.
object Stickers {

    // потолок стикера: большие PNG раздули бы пузыри и память
    const val MAX_BYTES = 1024 * 1024
    const val MAX_EDGE = 1024

    class Item(val id: String, val name: String, val width: Int, val height: Int)

    private fun dir(context: Context) = File(context.filesDir, "stickers")

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

    private fun stickerFile(context: Context, id: String) = File(dir(context), "$id.stk")

    // индекс читается при каждом обращении: набор мал, отдельный кэш
    // усложнил бы согласованность после wipe/переноса
    private fun readIndex(context: Context): MutableList<Item> {
        val file = indexFile(context)
        if (!file.exists()) return ArrayList()
        val json = decrypt(dir(context), file.readBytes())?.decodeToString() ?: return ArrayList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Item(
                    o.optString("i"),
                    o.optString("n"),
                    o.optInt("w", 0),
                    o.optInt("h", 0),
                )
            }
            out
        } catch (_: Exception) {
            ArrayList()
        }
    }

    @Synchronized
    private fun writeIndex(context: Context, items: List<Item>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().put("i", item.id).put("n", item.name).put("w", item.width).put("h", item.height))
        }
        indexFile(context).writeBytes(encrypt(dir(context), arr.toString().encodeToByteArray()))
    }

    // новый стикер в набор; null — байты не читаются
    @Synchronized
    fun save(context: Context, data: ByteArray, name: String, width: Int, height: Int): Item? {
        if (data.isEmpty() || data.size > MAX_BYTES || width <= 0 || height <= 0) return null
        val d = dir(context)
        d.mkdirs()
        val id = System.currentTimeMillis().toString(36) + "-" +
            CryptoPrimitives.randomBytes(4).joinToString("") { "%02x".format(it) }
        stickerFile(context, id).writeBytes(encrypt(d, data))
        val item = Item(id, name.take(64), width, height)
        val items = readIndex(context)
        items += item
        writeIndex(context, items)
        return item
    }

    fun list(context: Context): List<Item> = readIndex(context)

    // байты стикера для отправки/превью; null — запись утрачена
    fun load(context: Context, id: String): ByteArray? {
        val file = stickerFile(context, id)
        if (!file.exists()) return null
        return decrypt(dir(context), file.readBytes())
    }

    // удаление из набора; битые записи индекса вычищаются попутно
    @Synchronized
    fun delete(context: Context, id: String) {
        stickerFile(context, id).delete()
        val items = readIndex(context).filter { it.id != id }
        writeIndex(context, items)
    }

    // полный сброс (wipe): используется при удалении данных аккаунта
    @Synchronized
    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    // суммарный размер набора — для настроек хранилища
    fun sizeBytes(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile && it.name != "vault.key" }?.sumOf { it.length() } ?: 0L
}
