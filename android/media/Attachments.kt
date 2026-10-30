package chat.client.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

// Классы выбранных вложений: сжатое фото (для отправки) или файл как есть.
class Photo(val jpeg: ByteArray, val width: Int, val height: Int)

// Стикер (Фаза P3): изображение как есть — без пережатия в JPEG,
// иначе умрёт прозрачность (как у WebP/PNG-стикеров Telegram)
class StickerImage(val data: ByteArray, val width: Int, val height: Int, val name: String)

class Attachment(
    val data: ByteArray,
    val fileName: String,
    val mime: String,
    val isImage: Boolean,
    val photo: Photo? = null, // заполнен если isImage
)

// Решение по вложению ДО чтения байтов (А6): чистые функции без
// Android-зависимостей — тестируются на JVM. Фото пережимается, поэтому
// потолок исходника у него свой (PHOTO_SOURCE_LIMIT): прежний общий
// MAX_PLAIN отвергал 12–25-мегапиксельные снимки до сжатия.
enum class AttachmentSource { PHOTO, GIF, FILE }

// потолок исходника фото: больше — не читаем вовсе (B6)
const val PHOTO_SOURCE_LIMIT = 128L * 1024 * 1024

fun attachmentSourceOf(mime: String?, ext: String): AttachmentSource = when {
    mime == "image/gif" -> AttachmentSource.GIF
    mime?.startsWith("image/") == true -> AttachmentSource.PHOTO
    // HEIC/HEIF/AVIF из файлового пикера: mime без «image/», но
    // фреймворк декодирует — сжимаем как фото (A22)
    ext in setOf("heic", "heif", "avif") -> AttachmentSource.PHOTO
    else -> AttachmentSource.FILE
}

// Чтение выбранных файлов из галереи/пикера: изображения — сжатие до
// 2048px с учётом EXIF-ориентации (иначе повёрнутые снимки), GIF — как
// есть (пережатие убило бы анимацию), остальное — байты с проверкой
// размера ещё до чтения.
object Attachments {

    private const val TARGET = 2048 // большая сторона сжатого фото (умолчание)

    // потолок блоба на сервере и накладные расходы шифрования (как в Media)
    private const val MAX_BLOB = 10 * 1024 * 1024
    private const val OVERHEAD = 12 + 16 // IV + GCM-тег
    internal val MAX_PLAIN = (MAX_BLOB - OVERHEAD).toLong()

    // AK7: видео сверх лимита читаем для обрезки — но не выше потолка
    // памяти; ещё больше — прежняя честная ошибка
    val OVERSIZE_VIDEO_CAP = 64L * 1024 * 1024

    // читает фото (B6): размер из метаданных ДО чтения; декод — двумя
    // проходами по InputStream (границы → inSampleSize → пиксели), без
    // загрузки всего файла в байты. Прозрачность заливается белым —
    // JPEG без альфы не чернит PNG. target — большая сторона (Фаза AD5:
    // выбор размера при отправке, умолчание 2048)
    fun loadPhoto(resolver: ContentResolver, uri: Uri, target: Int = TARGET): Photo? {
        val size = querySize(resolver, uri)
        if (size > PHOTO_SOURCE_LIMIT) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Попутный фикс (найден живым зондом на устройстве): decodeStream
        // с inJustDecodeBounds по контракту возвращает null — прежний
        // elvis ронял загрузку фото ДО декода; валидность проверяет
        // следующая строка по заполненным границам
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target) sample = sample shl 1
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        // EXIF-ориентация: повёрнутые снимки хранят поворот в метаданных
        val rotated = applyExif(resolver, uri, decoded)
        // прозрачный PNG/WEBP на белой подложке — иначе JPEG даст чёрный фон
        val flat = if (rotated.hasAlpha()) flattenOnWhite(rotated) else rotated
        val width = flat.width
        val height = flat.height
        val out = ByteArrayOutputStream()
        try {
            flat.compress(Bitmap.CompressFormat.JPEG, 85, out)
        } finally {
            if (flat !== rotated && flat !== decoded) flat.recycle()
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
        }
        return Photo(out.toByteArray(), width, height)
    }

    // композит поверх белого: JPEG не хранит альфу
    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    @Suppress("DEPRECATION", "UnsafeDynamicallyLoadedCode") // framework ExifInterface: без новых зависимостей
    @android.annotation.SuppressLint("ExifInterface")
    private fun applyExif(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // читает вложение; null — не поддерживается или выше потолка сервера
    fun loadAttachment(resolver: ContentResolver, uri: Uri): Attachment? {
        // сначала размер из метаданных — большие файлы не читаем вовсе.
        // А6: у фото потолок ПЕРЕЖАТИЯ — PHOTO_SOURCE_LIMIT, а не MAX_PLAIN:
        // снимки 12–25 МБ с современных камер обязаны проходить через
        // «Поделиться» и файловый пикер
        val size = querySize(resolver, uri)

        val mime = resolver.getType(uri) ?: guessMime(uri)
        val name = queryFileName(resolver, uri) ?: uri.lastPathSegment ?: "file"
        val ext = name.substringAfterLast('.', "").lowercase()
        // файл без расширения пропускаем только если и MIME неизвестен
        if (ext.isNotEmpty() && ext !in Media.SUPPORTED_EXTENSIONS) return null

        val source = attachmentSourceOf(mime, ext)
        val limit = if (source == AttachmentSource.PHOTO) PHOTO_SOURCE_LIMIT else MAX_PLAIN
        if (size > limit) return null

        return when (source) {
            // GIF — как файл: пережатие в JPEG убило бы анимацию
            AttachmentSource.GIF -> {
                val data = readBytes(resolver, uri, size) ?: return null
                Attachment(data, name, "image/gif", false)
            }
            // сжатие меняет формат — имя и mime честно меняем на .jpg
            AttachmentSource.PHOTO -> {
                val photo = loadPhoto(resolver, uri) ?: return null
                Attachment(photo.jpeg, jpegName(name), "image/jpeg", true, photo)
            }
            AttachmentSource.FILE -> {
                val data = readBytes(resolver, uri, size) ?: return null
                Attachment(data, name, mime ?: "application/octet-stream", false)
            }
        }
    }

    // Фаза V5 (аналог «Send as file» у Telegram): исходное изображение
    // БЕЗ пережатия — байты как есть, оригинальные имя и mime; уходить
    // будет файлом, поэтому потолок — как у файлов (MAX_PLAIN)
    fun loadOriginalImage(resolver: ContentResolver, uri: Uri): Attachment? {
        val size = querySize(resolver, uri)
        if (size > MAX_PLAIN) return null
        val mime = resolver.getType(uri) ?: guessMime(uri) ?: return null
        if (!mime.startsWith("image/")) return null
        val name = queryFileName(resolver, uri) ?: uri.lastPathSegment ?: "image"
        val data = readBytes(resolver, uri, size) ?: return null
        return Attachment(data, name, mime, isImage = true)
    }

    // Фаза AB2: фото после редактора (повороты/обрезка) — пересжатие в
    // JPEG 85% с длинной стороной не больше 2048px, как у обычного фото
    fun editedPhoto(bmp: Bitmap): Photo? {
        val scale = (TARGET.toFloat() / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
        val scaled = if (scale < 1f) {
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        val width = scaled.width
        val height = scaled.height
        val ok = try {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        } catch (_: Exception) {
            false
        } finally {
            if (scaled !== bmp) scaled.recycle()
        }
        if (!ok) return null
        return Photo(out.toByteArray(), width, height)
    }

    // сжатие меняет формат — расширение честно меняем на .jpg
    private fun jpegName(name: String): String =
        if (name.lastIndexOf('.') > 0) name.substringBeforeLast('.') + ".jpg" else "$name.jpg"

    // аватар чата (Фаза U1, по образцу кропа фото профиля Telegram):
    // центр-кроп в квадрат и сжатие до 512px JPEG — блоб выходит
    // лёгким, фан-аут по всем чатам дёшев
    fun loadAvatar(resolver: ContentResolver, uri: Uri): Photo? {
        val photo = loadPhoto(resolver, uri) ?: return null
        val source = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size) ?: return null
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val cropped = Bitmap.createBitmap(source, x, y, side, side)
        val scale = (512f / side).coerceAtMost(1f)
        val out = try {
            if (scale < 1f) {
                val w = (side * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(cropped, w, w, true)
                val bytes = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
                if (scaled !== cropped) scaled.recycle()
                val size = w
                bytes to size
            } else {
                val bytes = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
                bytes to side
            }
        } finally {
            if (cropped !== source) cropped.recycle()
            source.recycle()
        }
        return Photo(out.first.toByteArray(), out.second, out.second)
    }

    // стикер из пикера: байты без пережатия, границы — отдельно (Фаза P3).
    // Потолки — как у набора: 1 МБ и 1024px по стороне
    fun loadSticker(resolver: ContentResolver, uri: Uri): StickerImage? {
        val size = querySize(resolver, uri)
        if (size > Stickers.MAX_BYTES) return null
        val bytes = readBytes(resolver, uri, size) ?: return null
        if (bytes.isEmpty() || bytes.size > Stickers.MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (maxOf(bounds.outWidth, bounds.outHeight) > Stickers.MAX_EDGE) return null
        val name = queryFileName(resolver, uri) ?: "sticker"
        return StickerImage(bytes, bounds.outWidth, bounds.outHeight, name)
    }

    // байты с потолком: при известном размере лимит уже проверен,
    // при неизвестном (-1) читаем вручную, свыше лимита — null
    private fun readBytes(resolver: ContentResolver, uri: Uri, size: Long): ByteArray? =
        readBytesUpTo(resolver, uri, size, MAX_PLAIN)

    // AK7: чтение с потолком памяти обрезки (без MAX_PLAIN)
    private fun readBytesUpTo(resolver: ContentResolver, uri: Uri, size: Long, cap: Long): ByteArray? {
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                val out = ByteArrayOutputStream(if (size > 0 && size <= cap) size.toInt() else 64 * 1024)
                val chunk = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > cap) return null
                    out.write(chunk, 0, n)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }

    // размер из метаданных провайдера, без чтения байтов; -1 — неизвестен.
    // Фаза AF1: публичный — честная ошибка «видео слишком большое» до чтения
    fun querySize(resolver: ContentResolver, uri: Uri): Long {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else -1
            } ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    // AK7: видео сверх лимита — прочесть байты для предложения обрезки;
    // null — не видео, непонятный размер или выше потолка памяти.
    // Обычная загрузка (loadAttachment) по-прежнему честно отказывает
    fun loadOversizeVideo(resolver: ContentResolver, uri: Uri): Attachment? {
        val mime = resolver.getType(uri) ?: guessMime(uri) ?: return null
        if (!mime.startsWith("video/")) return null
        val size = querySize(resolver, uri)
        if (size <= 0 || size > OVERSIZE_VIDEO_CAP) return null
        val name = queryFileName(resolver, uri) ?: uri.lastPathSegment ?: "video.mp4"
        val data = readBytesUpTo(resolver, uri, size, OVERSIZE_VIDEO_CAP) ?: return null
        return Attachment(data, name, mime, false)
    }

    private fun guessMime(uri: Uri): String? {
        val ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> null
        }
    }

    private fun queryFileName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
