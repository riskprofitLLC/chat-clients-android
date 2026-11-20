package chat.client.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever

// AK1 (аналог альбома в аудиоплеере Telegram): метаданные аудиофайла —
// название/исполнитель/обложка из встроенных тегов. Чтение из памяти
// через InMemoryMediaDataSource — расшифрованных файлов на диске нет.
class AudioMeta(
    val title: String?,
    val artist: String?,
    val art: Bitmap?,
)

object AudioMetaReader {

    private const val ART_EDGE = 512 // потолок обложки — экономия памяти

    // теги аудио; null — тегов нет или формат не разобрался
    fun read(bytes: ByteArray): AudioMeta? {
        if (bytes.size < 128) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(InMemoryMediaDataSource(bytes))
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() }
            val art = decodeArt(retriever.embeddedPicture)
            if (title == null && artist == null && art == null) null
            else AudioMeta(title, artist, art)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    // обложка со сжатием до ART_EDGE по большей стороне
    private fun decodeArt(raw: ByteArray?): Bitmap? {
        if (raw == null || raw.isEmpty()) return null
        val src = try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (_: Exception) {
            null
        } ?: return null
        if (src.width <= 0 || src.height <= 0) return null
        if (maxOf(src.width, src.height) <= ART_EDGE) return src
        val scale = ART_EDGE.toFloat() / maxOf(src.width, src.height)
        return try {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } catch (_: Exception) {
            src
        }
    }
}
