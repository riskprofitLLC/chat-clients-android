package chat.client.media

import android.media.MediaMetadataRetriever
import android.util.Base64
import chat.client.R
import chat.client.data.AppContainer
import chat.client.data.SessionInfo
import chat.client.domain.Envelope
import chat.client.domain.Markup
import chat.client.domain.ReplyFormat
import org.json.JSONObject

// Общая отправка через outbox (Фаза K2): один путь для диалога,
// share-таргета и быстрого ответа из уведомления. Стейджинг
// переехал сюда из DialogViewModel.
object OutboxSender {

    // потолок блоба на сервере и накладные расходы шифрования
    private const val MAX_BLOB = 10 * 1024 * 1024
    private const val OVERHEAD = 12 + 16 // IV + GCM-тег

    // payload текста: обычный, с цитатой ответа или беззвучный (Фаза X1)
    fun textPayload(
        text: String,
        replyAuthor: String? = null,
        replySnippet: String? = null,
        replyRef: String? = null,
        replyTargetTs: Long? = null,
        silent: Boolean = false, // Фаза X1
    ): ByteArray = if (replyRef != null && replyAuthor != null && replySnippet != null) {
        ReplyFormat.encode(replyAuthor, replySnippet, replyRef, replyTargetTs, text, silent)
    } else if (silent) {
        ReplyFormat.encodeSilent(text)
    } else {
        Envelope.escapeText(text)
    }

    // постановка текста в очередь; null — пустой текст
    fun addText(
        container: AppContainer,
        s: SessionInfo,
        chatId: String,
        text: String,
    ): chat.client.data.Outbox.Entry? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return container.outbox.add(
            chatId, textPayload(trimmed), Markup.strip(trimmed).take(64), login = s.login,
        )
    }

    // staged-вложение: [IV 12Б][AES-GCM] контент-ключом + JSON-дескриптор
    fun stage(data: ByteArray, descriptor: JSONObject): Pair<ByteArray, JSONObject>? {
        if (data.size + OVERHEAD > MAX_BLOB) return null
        return try {
            val key = chat.crypto.CryptoPrimitives.randomBytes(32)
            val iv = chat.crypto.CryptoPrimitives.randomBytes(12)
            val blob = iv + chat.crypto.CryptoPrimitives.aesGcmEncrypt(key, iv, data, ByteArray(0))
            blob to descriptor.put("key", Base64.encodeToString(key, Base64.NO_WRAP))
        } catch (_: Exception) {
            null
        }
    }

    fun photoDescriptor(
        photo: Photo,
        spoiler: Boolean = false,
        album: String? = null,
        silent: Boolean = false, // Фаза X1
        viewOnce: Boolean = false, // Фаза AB1: одноразовое фото
    ): JSONObject {
        val json = JSONObject()
            .put("type", "media")
            .put("size", photo.jpeg.size.toLong())
            .put("w", photo.width)
            .put("h", photo.height)
            .put("thumb", Media.thumbB64Of(photo.jpeg))
        // спойлер (Фаза O2): опциональное поле дескриптора
        if (spoiler) json.put("sp", 1)
        // альбом (Фаза V1): общий ид пачки — у получателя склеит сетку
        if (album != null) json.put("al", album)
        // беззвучная отправка (Фаза X1): опциональное поле дескриптора
        if (silent) json.put("sl", 1)
        // одноразовое (Фаза AB1): опциональное поле, старые клиенты игнорируют
        if (viewOnce) json.put("vo", 1)
        return json
    }

    fun fileDescriptor(att: Attachment, silent: Boolean = false, viewOnce: Boolean = false): JSONObject {
        val json = JSONObject()
            .put("type", "file")
            .put("size", att.data.size.toLong())
            .put("name", att.fileName)
            .put("mime", att.mime)
        // беззвучная отправка (Фаза X1): опциональное поле дескриптора
        if (silent) json.put("sl", 1)
        // одноразовое видео (Фаза AB1): опциональное поле
        if (viewOnce) json.put("vo", 1)
        // видео-файл (Фаза P2): кадр/размеры/длительность для карточки —
        // как опциональные поля, старые клиенты их игнорируют
        if (att.mime.startsWith("video/")) {
            videoMeta(att.data)?.let { (w, h, dur, thumb) ->
                if (w > 0) json.put("w", w)
                if (h > 0) json.put("h", h)
                if (dur > 0) json.put("dur", dur)
                if (thumb.isNotEmpty()) json.put("thumb", thumb)
            }
        }
        // GIF (Фаза U2): размеры кадра — для аспекта анимированной карточки
        if (att.mime == "image/gif") {
            gifBounds(att.data)?.let { (w, h) ->
                if (w > 0) json.put("w", w)
                if (h > 0) json.put("h", h)
            }
        }
        // аудио-файл (Фаза U4): длительность для карточки плеера
        if (att.mime.startsWith("audio/")) {
            audioDurationSec(att.data)?.let { if (it > 0) json.put("dur", it) }
        }
        return json
    }

    // видео-кружок (Фаза S1): file-дескриптор с флагом vn + метаданные;
    // старые клиенты видят обычную видео-карточку с именем файла
    fun videoNoteDescriptor(data: ByteArray, durationSec: Int): JSONObject {
        val json = JSONObject()
            .put("type", "file")
            .put("size", data.size.toLong())
            .put("name", "video-note.mp4")
            .put("mime", "video/mp4")
            .put("vn", 1)
        if (durationSec > 0) json.put("dur", durationSec)
        videoMeta(data)?.let { (w, h, _, thumb) ->
            if (w > 0) json.put("w", w)
            if (h > 0) json.put("h", h)
            if (thumb.isNotEmpty()) json.put("thumb", thumb)
        }
        return json
    }

    // стикер (Фаза P3): media-дескриптор с флагом stk; миниатюра — на белом
    // фоне, чтобы старый клиент видел фото, а не «медиа недоступно»
    fun stickerDescriptor(st: StickerImage): JSONObject {
        val json = JSONObject()
            .put("type", "media")
            .put("size", st.data.size.toLong())
            .put("w", st.width)
            .put("h", st.height)
            .put("stk", 1)
        val thumb = runCatching {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(st.data, 0, st.data.size)
            if (bmp != null) {
                val b64 = Base64.encodeToString(Media.thumbJpeg(bmp), Base64.NO_WRAP)
                bmp.recycle()
                b64
            } else ""
        }.getOrDefault("")
        if (thumb.isNotEmpty()) json.put("thumb", thumb)
        return json
    }

    // метаданные видео: кадр-миниатюра, размеры, длительность; null — битый файл
    private fun videoMeta(data: ByteArray): VideoMeta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(InMemoryMediaDataSource(data))
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = try {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            }
            val thumb = if (frame != null) {
                try {
                    Base64.encodeToString(Media.thumbJpeg(frame), Base64.NO_WRAP)
                } finally {
                    frame.recycle()
                }
            } else {
                ""
            }
            VideoMeta(w, h, (durMs / 1000).toInt().coerceIn(0, 86_400), thumb)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private data class VideoMeta(val width: Int, val height: Int, val durationSec: Int, val thumbB64: String)

    // размеры первого кадра GIF (Фаза U2); null — битый файл
    private fun gifBounds(data: ByteArray): Pair<Int, Int>? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (_: Exception) {
            null
        }
    }

    // длительность аудио-файла из памяти (Фаза U4); null — не декодируется
    private fun audioDurationSec(data: ByteArray): Int? {
        val player = android.media.MediaPlayer()
        return try {
            player.setDataSource(InMemoryMediaDataSource(data))
            player.prepare()
            (player.duration / 1000).coerceIn(0, 86_400)
        } catch (_: Exception) {
            null
        } finally {
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    // staged-запись в очередь: дескриптор без ключа, ключ — отдельно
    fun addStaged(
        container: AppContainer,
        s: SessionInfo,
        chatId: String,
        staged: Pair<ByteArray, JSONObject>,
        preview: String,
    ): Boolean {
        val (blob, descriptor) = staged
        val key = Base64.decode(descriptor.getString("key"), Base64.NO_WRAP)
        val descWithoutKey = JSONObject(descriptor.toString()).apply { remove("key") }
        return try {
            container.outbox.addAttachment(chatId, blob, descWithoutKey, key, preview, s.login)
            true
        } catch (_: Exception) {
            false
        }
    }

    // фото одним шагом: staged + очередь; false — велико или ошибка
    fun sendPhoto(
        container: AppContainer,
        s: SessionInfo,
        chatId: String,
        photo: Photo,
        album: String? = null,
    ): Boolean {
        val staged = stage(photo.jpeg, photoDescriptor(photo, album = album)) ?: return false
        return addStaged(container, s, chatId, staged, container.app.getString(R.string.photo))
    }

    // стикер одним шагом (Фаза P3): staged + очередь
    fun sendSticker(container: AppContainer, s: SessionInfo, chatId: String, st: StickerImage): Boolean {
        val staged = stage(st.data, stickerDescriptor(st)) ?: return false
        return addStaged(container, s, chatId, staged, container.app.getString(R.string.sticker))
    }

    // вложение из share/пикера: изображение — как фото, остальное — файл
    fun sendAttachment(container: AppContainer, s: SessionInfo, chatId: String, att: Attachment): Boolean {
        val (data, preview) = if (att.isImage && att.photo != null) {
            att.photo.jpeg to container.app.getString(R.string.photo)
        } else {
            att.data to att.fileName
        }
        val descriptor = if (att.isImage && att.photo != null) photoDescriptor(att.photo) else fileDescriptor(att)
        val staged = stage(data, descriptor) ?: return false
        return addStaged(container, s, chatId, staged, preview)
    }
}
