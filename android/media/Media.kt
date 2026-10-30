package chat.client.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.util.Base64
import android.util.LruCache
import chat.client.domain.MediaInfo
import chat.client.domain.MediaJson
import chat.client.net.RelayApi
import chat.client.net.RelayException
import chat.crypto.CryptoPrimitives
import chat.crypto.KeyVaultAndroid
import java.io.ByteArrayOutputStream
import java.io.File
import android.util.Log

// краткий псевдоним внутри модуля
typealias Info = MediaInfo

// Медиа: локальное зашифрованное хранилище расшифрованных блобов + утилиты.
// Блоб на сервере: [IV 12Б][AES-GCM(ct||tag)] контент-ключом; AAD пустой
// (media_id неизвестен до загрузки, перезашифровать нельзя). Сервер —
// транзит с TTL: расшифрованная копия хранится локально.
//
object Media {

    // миниатюра: ребро и потолок base64-дескриптора MediaJson (B6)
    const val THUMB_EDGE = 480
    private const val THUMB_MAX_BYTES = 96 * 1024 // 131 072 base64-символов

    // потолок сервера: 10 МБ на блоб
    private const val MAX_BLOB = 10 * 1024 * 1024
    private const val OVERHEAD = 12 + 16 // IV + GCM-тег

    // строгий разбор дескриптора медиа: message() кладёт JSON в конверт
    // TYPE_MEDIA — разворачиваем его; легаси-контент мог быть сырым JSON
    fun parse(content: ByteArray): Info? {
        val payload = chat.client.domain.Envelope.decode(content)
            ?.takeIf { it.type == chat.client.domain.Envelope.TYPE_MEDIA }
            ?.payload
            ?: content
        return MediaJson.parse(payload)
    }

    // контент сообщения: конверт TYPE_MEDIA с JSON-дескриптором внутри
    fun message(info: Info): ByteArray = MediaJson.message(info)

    // ─── миниатюры: LruCache + декод без повторов (A22) ───

    // потолок кэша — БАЙТЫ, а не штуки (B8): 64 bitmap-а 480px — это
    // десятки МБ в куче; берём maxMemory/8 и считаем размер через sizeOf
    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L).coerceAtLeast(8L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // только кэш — мгновенно, для композиции
    fun cachedThumb(info: Info): Bitmap? {
        if (info.thumbB64.isEmpty()) return null
        return thumbCache.get(info.id)
    }

    // декод миниатюры + кэш; вызывать с фона (A22)
    fun decodeThumb(info: Info): Bitmap? {
        if (info.thumbB64.isEmpty()) return null
        thumbCache.get(info.id)?.let { return it }
        return try {
            val bytes = Base64.decode(info.thumbB64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                thumbCache.put(info.id, it)
            }
        } catch (_: Exception) {
            null
        }
    }

    // миниатюра из готового JPEG-массива (без лишнего Bitmap в VM)
    fun thumbB64Of(jpeg: ByteArray): String {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return ""
        return try {
            Base64.encodeToString(thumbJpeg(source), Base64.NO_WRAP)
        } finally {
            source.recycle()
        }
    }

    // ─── единый downsampling (B3): все полноэкранные фото идут через него ───

    fun decodeDownsampled(bytes: ByteArray, maxEdge: Int = 2048): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample = sample shl 1
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)) {
            "не удалось декодировать изображение"
        }
    }

    // ─── локальное хранилище расшифрованных медиа ───

    private fun mediaDir(context: Context) = File(context.filesDir, "media")

    // 4.2: единый KeystoreDataKey с кэшем — разворот на каждый вызов был лишней работой
    private fun dataKey(dir: File): ByteArray = chat.crypto.KeystoreDataKey.dataKey(dir)

    // media_id приходит из чужого JSON: только плоское имя, без обхода каталогов
    private fun safeName(mediaId: String): String? =
        if (Regex("^[0-9a-zA-Z_-]{1,128}$").matches(mediaId)) "$mediaId.media" else null

    fun save(context: Context, mediaId: String, data: ByteArray) =
        save(mediaDir(context), mediaId, data)

    fun save(dir: File, mediaId: String, data: ByteArray) {
        val name = safeName(mediaId) ?: throw IllegalArgumentException("некорректный media_id")
        dir.mkdirs()
        val iv = CryptoPrimitives.randomBytes(12)
        val blob = iv + CryptoPrimitives.aesGcmEncrypt(dataKey(dir), iv, data, ByteArray(0))
        // A6: атомарная запись — обрыв питания оставляет прежнюю (или никакую)
        // копию, но не битый файл, который exists() считает целым и не качает заново
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(blob)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw java.io.IOException("media: атомарная замена не удалась: $name")
            }
        } finally {
            tmp.delete()
        }
    }

    fun load(context: Context, mediaId: String): ByteArray? =
        load(mediaDir(context), mediaId)

    fun load(dir: File, mediaId: String): ByteArray? {
        val name = safeName(mediaId) ?: return null
        val file = File(dir, name)
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            CryptoPrimitives.aesGcmDecrypt(
                dataKey(dir),
                blob.copyOfRange(0, 12),
                blob.copyOfRange(12, blob.size),
                ByteArray(0),
            )
        } catch (e: java.security.GeneralSecurityException) {
            // A6: битая копия удаляется — exists() больше не врёт, докачка
            // при следующем запросе восстановит файл
            file.delete()
            null
        } catch (e: Exception) {
            // sonnet-фикс: IO-сбой (обрыв чтения) — файл НЕ удаляем:
            // блоб с сервера может уйти по TTL, потеря стала бы невосполнимой
            Log.w("Media", "медиа $mediaId временно не читается", e)
            null
        }
    }

    fun exists(context: Context, mediaId: String): Boolean =
        exists(mediaDir(context), mediaId)

    fun exists(dir: File, mediaId: String): Boolean {
        val name = safeName(mediaId) ?: return false
        return File(dir, name).exists()
    }

    // удаление локальной копии; safeName не даёт выйти за media-каталог
    fun delete(context: Context, mediaId: String): Boolean {
        val name = safeName(mediaId) ?: return false
        return File(mediaDir(context), name).delete()
    }

    // суммарный размер локальных медиа — для настроек хранилища
    fun size(context: Context): Long =
        mediaDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".media") }
            ?.sumOf { it.length() } ?: 0L

    // байты по набору id — строки «по чатам» экрана хранилища (Фаза S2)
    fun sizeOf(context: Context, ids: Collection<String>): Long {
        val dir = mediaDir(context)
        var sum = 0L
        for (id in ids) {
            val name = safeName(id) ?: continue
            val file = File(dir, name)
            if (file.exists()) sum += file.length()
        }
        return sum
    }

    // удаление локальных копий по набору id (Фаза S2: чистка медиа чата)
    fun deleteAll(context: Context, ids: Collection<String>): Int {
        var removed = 0
        for (id in ids) {
            if (delete(context, id)) removed++
        }
        return removed
    }

    // очистка всего медиа-кэша (настройки → Чаты)
    fun clearAll(context: Context): Boolean = mediaDir(context).deleteRecursively()

    // Фаза AE1 (аналог «Keep media» у Telegram): зачистка локальных копий
    // старше срока; protectIds — блобы, которые не трогаем (аватары);
    // возвращает число удалённых файлов
    fun sweepByAge(context: Context, keepDays: Int, nowMs: Long, protectIds: Set<String>): Int {
        val cutoff = chat.client.domain.MediaRetention.cutoffMs(nowMs, keepDays)
        if (cutoff == Long.MIN_VALUE) return 0
        val dir = mediaDir(context)
        var removed = 0
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".media") }
            ?: return 0
        for (file in files) {
            val id = file.name.removeSuffix(".media")
            if (id in protectIds) continue
            if (chat.client.domain.MediaRetention.isExpired(file.lastModified(), cutoff)) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    // очистка временных файлов при старте приложения
    fun cleanTemp(context: Context) {
        File(context.cacheDir, "shared").deleteRecursively()
        for (file in context.cacheDir.listFiles() ?: emptyArray()) {
            if (file.isFile && (file.name.endsWith(".m4a") || file.name.startsWith("import"))) {
                file.delete()
            }
        }
    }

    // ─── загрузка на сервер ───

    // шифруем фото контент-ключом и грузим блоб; дескриптор — для JSON
    fun encryptAndUpload(api: RelayApi, token: String, photo: Photo, thumb: ByteArray): Info {
        if (photo.jpeg.size + OVERHEAD > MAX_BLOB) {
            throw RelayException(413, "фото слишком большое после сжатия: ${photo.jpeg.size / 1024 / 1024} МБ")
        }
        return uploadBlob(api, token, photo.jpeg).let { (id, key) ->
            Info(
                id = id, key = key, size = photo.jpeg.size.toLong(),
                width = photo.width, height = photo.height,
                thumbB64 = Base64.encodeToString(thumb, Base64.NO_WRAP),
            )
        }
    }

    // шифруем файл и грузим блоб; дескриптор — для JSON
    fun encryptAndUploadFile(api: RelayApi, token: String, att: Attachment): Info {
        if (att.data.size + OVERHEAD > MAX_BLOB) {
            throw RelayException(413, "файл слишком большой (макс 10 МБ)")
        }
        return uploadBlob(api, token, att.data).let { (id, key) ->
            Info(
                id = id, key = key, size = att.data.size.toLong(),
                width = 0, height = 0, thumbB64 = "",
                name = att.fileName, mime = att.mime, type = "file",
            )
        }
    }

    // шифруем голосовое и грузим блоб; дескриптор — type=voice с волной
    fun encryptAndUploadVoice(
        api: RelayApi,
        token: String,
        data: ByteArray,
        durationSec: Int,
        waveform: ByteArray? = null, // амплитуды 0..255, ~10 сэмплов/сек
    ): Info {
        if (data.size + OVERHEAD > MAX_BLOB) {
            throw RelayException(413, "голосовое слишком длинное (макс 10 МБ)")
        }
        return uploadBlob(api, token, data).let { (id, key) ->
            Info(
                id = id, key = key, size = data.size.toLong(),
                width = 0, height = 0, thumbB64 = "",
                type = "voice", duration = durationSec, waveform = waveform,
            )
        }
    }

    private fun uploadBlob(api: RelayApi, token: String, plain: ByteArray): Pair<String, ByteArray> {
        val key = CryptoPrimitives.randomBytes(32)
        val iv = CryptoPrimitives.randomBytes(12)
        // AAD пустой — решение зафиксировано, см. комментарий объекта
        val blob = iv + CryptoPrimitives.aesGcmEncrypt(key, iv, plain, ByteArray(0))
        val id = api.uploadMedia(token, blob)
        return id to key
    }

    // миниатюра JPEG ~480px (B6: 320 мылило на широких пузырях),
    // PNG-прозрачность заливаем белым — иначе JPEG превращает её
    // в чёрный фон (A22). Подгоняем под потолок base64 в MediaJson
    fun thumbJpeg(source: Bitmap, maxEdge: Int = THUMB_EDGE): ByteArray {
        val scale = maxEdge.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val out = ByteArrayOutputStream()
        if (scaled.hasAlpha()) {
            val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(AColor.WHITE)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            flat.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (flat !== scaled && flat !== source) flat.recycle()
        } else {
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        if (scaled !== source) scaled.recycle()
        // потолок thumb в MediaJson: 131 072 символа base64 ≈ 96 КБ блоба.
        // Не влезло — уменьшаем качество, затем размер (B6)
        var bytes = out.toByteArray()
        var edge = maxEdge
        while (bytes.size > THUMB_MAX_BYTES && edge > 160) {
            edge = (edge * 0.8f).toInt()
            val s2 = edge.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1)
            val w2 = (source.width * s2).toInt().coerceAtLeast(1)
            val h2 = (source.height * s2).toInt().coerceAtLeast(1)
            val sc2 = Bitmap.createScaledBitmap(source, w2, h2, true)
            val o2 = ByteArrayOutputStream()
            sc2.compress(Bitmap.CompressFormat.JPEG, 60, o2)
            if (sc2 !== source) sc2.recycle()
            bytes = o2.toByteArray()
        }
        return bytes
    }

    // ─── вложения из галереи/файл-пикера ───

    // разрешённые форматы вложений (10 МБ — потолок сервера); apk разрешён
    // осознанно: получатель сам решает, устанавливать ли файл. heic/heif/avif
    // декодируются фреймворком на новых API (A22)
    val SUPPORTED_EXTENSIONS = setOf(
        // изображения
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif",
        // документы
        "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "rtf", "csv", "json", "xml", "html", "md", "epub",
        // видео (10 МБ)
        "mp4", "webm", "mov", "3gp", "mkv",
        // аудио
        "mp3", "ogg", "wav", "m4a", "opus", "flac", "aac",
        // архивы
        "zip", "rar", "7z", "tar", "gz",
        // прочее
        "apk",
    )
}
