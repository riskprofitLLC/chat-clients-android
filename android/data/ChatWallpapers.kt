package chat.client.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream

// чистое имя файла обоев: chatId (логин или grp_*) + .jpg, без слэшей
fun wallpaperFileName(chatId: String): String = "$chatId.jpg"

// Локальные обои чата: filesDir/wallpapers/*.jpg (заданы из галереи)
class ChatWallpapers(private val context: Context) {
    private val dir: File
        get() {
            val d = File(context.filesDir, "wallpapers")
            if (!d.exists()) d.mkdirs()
            return d
        }

    fun fileName(chatId: String): String = wallpaperFileName(chatId)

    fun save(chatId: String, jpeg: ByteArray) {
        val target = File(dir, fileName(chatId))
        val tmp = File(dir, "${fileName(chatId)}.tmp")
        FileOutputStream(tmp).use { it.write(jpeg) }
        // 1.3: rename поверх существующего — delete+rename оставлял окно,
        // обрыв в котором уничтожал прежние обои
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun load(chatId: String): ByteArray? {
        val f = File(dir, fileName(chatId))
        return if (f.exists() && f.canRead()) f.readBytes() else null
    }

    fun remove(chatId: String) {
        val f = File(dir, fileName(chatId))
        if (f.exists()) f.delete()
    }

    fun clearAll() {
        dir.listFiles()?.forEach { if (it.extension == "jpg" || it.extension == "tmp") it.delete() }
    }
}
