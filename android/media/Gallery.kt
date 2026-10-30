package chat.client.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import chat.client.data.AppContainer
import chat.client.data.SessionInfo

// «Сохранить в галерею» и «Поделиться» (Фаза X3, аналог saveImage/share
// у Telegram): расшифрованные байты уходят в системный MediaStore —
// фото в Pictures/Chat, видео в Movies/Chat, прочее — в Downloads
// (API 29+). На API 24–28 фото/видео пишутся во внешний MediaStore
// без RELATIVE_PATH, файлы — только через SAF («Сохранить в загрузки»).
object Gallery {

    enum class Kind { IMAGE, VIDEO, OTHER }

    // 3.2: внятная причина неудачи сохранения — UI показывает её вместо
    // молчаливого «не получилось»
    enum class SaveResult { OK, NO_PERMISSION, UNSUPPORTED, ERROR }

    // куда кладём по mime — чистая логика, тестируется на JVM
    fun kindOf(mime: String?): Kind = when {
        mime == null -> Kind.OTHER
        mime.startsWith("image/") -> Kind.IMAGE
        mime.startsWith("video/") -> Kind.VIDEO
        else -> Kind.OTHER
    }

    // уникальное имя вида IMG_1699..._123.jpg
    fun displayName(kind: Kind, ext: String, now: Long = System.currentTimeMillis()): String {
        val prefix = when (kind) {
            Kind.IMAGE -> "IMG"
            Kind.VIDEO -> "VID"
            Kind.OTHER -> "FILE"
        }
        return "${prefix}_${now}${if (ext.isEmpty()) "" else ".$ext"}"
    }

    // расширение из имени файла или mime
    fun extOf(name: String?, mime: String?): String {
        val raw = name?.let { if (it.contains('/')) it.substringAfterLast('/') else it } ?: ""
        val ext = raw.substringAfterLast('.', "")
        if (ext.length in 1..8 && ext.all { it.isLetterOrDigit() }) return ext.lowercase()
        return when {
            mime == null -> ""
            mime.startsWith("image/") -> mime.removePrefix("image/").lowercase()
            mime.startsWith("video/") -> mime.removePrefix("video/").lowercase()
            mime.startsWith("audio/") -> mime.removePrefix("audio/").lowercase()
            else -> ""
        }
    }

    // сохранение в системную галерею/загрузки; причина неудачи различима:
    // NO_PERMISSION — API<29 без WRITE_EXTERNAL_STORAGE (3.2),
    // UNSUPPORTED — файлы до API 29 (SAF-путь), ERROR — сбой записи.
    // Осиротевшая IS_PENDING-запись удаляется при любом сбое записи
    fun save(context: Context, bytes: ByteArray, mime: String, name: String): SaveResult {
        val kind = kindOf(mime)
        if (Build.VERSION.SDK_INT < 29 && kind == Kind.OTHER) return SaveResult.UNSUPPORTED
        if (Build.VERSION.SDK_INT < 29 && !hasLegacyWrite(context)) {
            return SaveResult.NO_PERMISSION
        }
        var uri: android.net.Uri? = null
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    when (kind) {
                        Kind.IMAGE -> put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Chat")
                        Kind.VIDEO -> put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Chat")
                        Kind.OTHER -> put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Chat")
                    }
                }
            }
            val collection = when (kind) {
                Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                Kind.OTHER -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            uri = resolver.insert(collection, values) ?: return SaveResult.ERROR
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                resolver.delete(uri, null, null)
                return SaveResult.ERROR
            }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            return SaveResult.OK
        } catch (_: Exception) {
            // сбой записи: осиротевшая pending-запись не мусорит в галерее
            uri?.let { context.contentResolver.delete(it, null, null) }
            return SaveResult.ERROR
        }
    }

    // 3.2: проверка runtime-разрешения записи на API 24–28
    fun hasLegacyWrite(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

// действия над открытым медиа: сохранить наружу и поделиться (Фаза X3)
class MediaActions(private val container: AppContainer) {

    private val context: Context = container.app

    // байты в галерею/загрузки одним тапом; причина неудачи различима (3.2)
    fun saveToGallery(bytes: ByteArray, info: chat.client.domain.MediaInfo): Gallery.SaveResult {
        val mime = if (!info.mime.isNullOrEmpty()) {
            info.mime
        } else if (info.type == "media") {
            "image/jpeg"
        } else {
            "application/octet-stream"
        }
        val name = Gallery.displayName(Gallery.kindOf(mime), Gallery.extOf(info.name, mime))
        return Gallery.save(context, bytes, mime, name)
    }

    // расшифрованная копия наружу через системный шар; null — медиа
    // недоступно (транзит истёк) (A22: опасные типы до этого не доходят)
    fun share(session: SessionInfo, info: chat.client.domain.MediaInfo): Intent? {
        val mime = info.mime ?: if (info.type == "media") "image/jpeg" else "application/octet-stream"
        return try {
            val bytes = container.media.getBytes(session, info) ?: return null
            val copy = SharedFiles.exportCopy(context, info.name ?: "media", bytes)
            SharedFiles.shareIntent(context, copy, mime)
        } catch (_: Exception) {
            null
        }
    }
}
