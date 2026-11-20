package chat.client.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

// AI10 (аналог video trim у Telegram): обрезка видео ПЕРЕСБОРКОЙ
// контейнера — MediaExtractor+MediaMuxer копируют сэмплы диапазона без
// пересжатия: быстро и без потери качества. Сбой контейнера — false,
// вызывающий честно оставляет оригинал.
object VideoTrimmer {

    class TrimException(message: String) : Exception(message)

    // длительность видео из памяти, мс; null — не декодируется
    fun durationMs(data: ByteArray): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(InMemoryMediaDataSource(data))
                val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: return null
                if (ms > 0) ms else null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // обрезка [startMs, endMs): байты ролика → байты нового MP4.
    // Бросает TrimException — контейнер не разобрался или треков нет;
    // пустой результат — тоже ошибка: ролик обязан стать короче.
    // AL6 (аналог Mute у Telegram): keepAudio=false выбрасывает
    // аудиодорожки — без пересжатия, той же пересборкой
    @Throws(TrimException::class)
    fun trim(
        data: ByteArray,
        startMs: Long,
        endMs: Long,
        cacheDir: File,
        keepAudio: Boolean = true,
    ): ByteArray {
        cacheDir.mkdirs()
        val out = File(cacheDir, "trim-${System.nanoTime()}.mp4")
        try {
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // карта исходных дорожек → дорожки муксера (только аудио/видео)
            val map = HashMap<Int, Int>()
            var tracks = 0
            try {
                val probe = MediaExtractor()
                probe.setDataSource(InMemoryMediaDataSource(data))
                for (track in 0 until probe.trackCount) {
                    val format = probe.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        // AL6: аудио выбрасывается по флагу
                        if (!keepAudio && mime.startsWith("audio/")) continue
                        map[track] = muxer.addTrack(format)
                        tracks++
                    }
                }
                probe.release()
            } catch (e: Exception) {
                try {
                    muxer.release()
                } catch (_: Exception) {
                }
                throw e
            }
            if (tracks == 0) {
                muxer.release()
                throw TrimException("нет дорожек")
            }
            muxer.start()
            try {
                val startUs = startMs * 1000
                val endUs = endMs * 1000
                val buffer = ByteBuffer.allocate(1 shl 20)
                for ((srcTrack, dstTrack) in map) {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(InMemoryMediaDataSource(data))
                        extractor.selectTrack(srcTrack)
                        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        val info = MediaCodec.BufferInfo()
                        while (true) {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val pts = extractor.sampleTime
                            // [start, end): сэмпл на границе конца уходит —
                            // иначе ролик на секунду длиннее ожидаемого
                            if (pts >= endUs) break
                            info.offset = 0
                            info.size = size
                            // сдвигаем к нулю: ролик начинается с места разреза
                            info.presentationTimeUs = pts - startUs
                            val flags = extractor.sampleFlags
                            if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 &&
                                android.os.Build.VERSION.SDK_INT >= 28
                            ) {
                                info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else {
                                info.flags = 0
                            }
                            if (info.presentationTimeUs >= 0) {
                                muxer.writeSampleData(dstTrack, buffer, info)
                            }
                            if (!extractor.advance()) break
                        }
                    } finally {
                        try {
                            extractor.release()
                        } catch (_: Exception) {
                        }
                    }
                }
            } finally {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
                muxer.release()
            }
            val bytes = out.readBytes()
            if (bytes.isEmpty()) throw TrimException("пустой результат")
            return bytes
        } finally {
            out.delete()
        }
    }
}
