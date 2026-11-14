package chat.client.media

import android.content.Context
import chat.client.data.AppContainer
import chat.client.data.SessionInfo
import chat.client.data.downloadAndStoreMedia
import chat.client.domain.MediaInfo
import java.io.File

// Единая точка доступа к байтам медиа: локальная копия или транзит.
// Заменяет пять дублирующихся вызовов «Media.load иначе download».
class MediaRepository(private val container: AppContainer) {

    private val context: Context = container.app

    // расшифрованные байты: локально или из транзита сервера; null — истекло
    fun getBytes(session: SessionInfo, info: MediaInfo): ByteArray? {
        Media.load(context, info.id)?.let { return it }
        if (info.id.isEmpty()) return null
        return try {
            downloadAndStoreMedia(context, session.api, session.token, info.id, info.key)
        } catch (_: Exception) {
            null
        }
    }

    // размер медиа-кэша
    fun cacheSize(): Long = Media.size(context)

    fun clearAll(): Boolean = Media.clearAll(context)

    // удаление локальной копии, если на неё не осталось ссылок в истории
    // (то же медиа может быть переслано в другой чат — считаем ссылки)
    fun deleteIfUnused(session: SessionInfo, mediaId: String) {
        if (mediaId.isEmpty()) return
        for (chatId in session.store.chatIds()) {
            for (record in session.store.history(chatId, Int.MAX_VALUE)) {
                if (contains(record.content, mediaId.encodeToByteArray())) return
            }
        }
        Media.delete(context, mediaId)
    }

    // наивный поиск подстроки в байтах
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    // расшифрованная копия для внешнего приложения (FileProvider)
    fun exportCopy(fileName: String, bytes: ByteArray): File =
        SharedFiles.exportCopy(context, fileName, bytes)
}
