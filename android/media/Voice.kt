package chat.client.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import chat.client.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

// Воспроизведение из памяти: расшифрованные байты не пишутся на диск.
// MediaDataSource доступен с API 23 — расширять буфер нельзя, только читать.
class InMemoryMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1 // EOF
        val start = position.toInt()
        val count = minOf(size, data.size - start)
        System.arraycopy(data, start, buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

// Запись голосовых (AAC 16 кГц моно). Класс с жизненным циклом: старт со
// свободного потока (prepare — диск-IO), стоп/отмена — из любого потока.
// Пауза (Фаза U3, по образцу pause в композере Telegram): MediaRecorder
// pause/resume, длительность — чистое время записи без пауз.
class VoiceRecorder(private val cacheDir: File) {

    class Recorded(val data: ByteArray, val durationSec: Int)

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStart = 0L
    private var pausedMs = 0L // суммарное время паузы
    private var pauseStart = 0L

    val isRecording: Boolean get() = recorder != null

    // пауза активна: запись идёт, но время стоит
    val isPaused: Boolean
        get() = recorder != null && pauseStart > 0L

    // запуск записи; повторный вызов отменяет незакрытую прошлую
    @Throws(IOException::class)
    fun start() {
        cancel()
        pausedMs = 0L // новая запись начинается без прошлого времени пауз (U3)
        pauseStart = 0L
        val file = File(cacheDir, "voice-${System.nanoTime()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder() else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(24_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            throw IOException("микрофон недоступен: ${e.message}", e)
        }
        recorder = r
        recordFile = file
        recordStart = System.currentTimeMillis()
    }

    // текущая амплитуда 0..32767 — для отрисовки волны при записи;
    // на паузе всегда ноль
    fun maxAmplitude(): Int = try {
        if (isPaused) 0 else recorder?.maxAmplitude ?: 0
    } catch (_: Exception) {
        0
    }

    // пауза (Фаза U3): MediaRecorder.pause с API 24; на паузе снова — resume
    fun togglePause(): Boolean {
        val r = recorder ?: return false
        return try {
            if (pauseStart > 0L) {
                r.resume()
                pausedMs += System.currentTimeMillis() - pauseStart
                pauseStart = 0L
                false
            } else {
                r.pause()
                pauseStart = System.currentTimeMillis()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    // чистое время записи без пауз, мс. AG-фикс: до завершения асинхронного
    // старта MediaRecorder recordStart==0 и разница равнялась эпохе — таймер
    // записи на живом железе показывал мусор первые сотни мс, а тесты/стоп
    // считали «уже секунду писали». Пока старт не завершился — честный ноль.
    // AL-раунд: арифметика едина с кружками — domain.RecordPause
    fun activeDurationMs(): Long =
        chat.client.domain.RecordPause.elapsedMs(
            recordStart, System.currentTimeMillis(), pausedMs, pauseStart,
        )

    // стоп: данные или null, если запись короче 1 секунды
    fun stop(): Recorded? {
        val r = recorder ?: return null
        val file = recordFile
        recorder = null
        recordFile = null
        val durationMs = activeDurationMs()
        if (pauseStart > 0L) {
            pausedMs += System.currentTimeMillis() - pauseStart
            pauseStart = 0L
        }
        val data = try {
            r.stop()
            file?.readBytes()
        } catch (_: Exception) {
            null
        }
        r.release()
        file?.delete()
        recordStart = 0L
        if (data == null || data.isEmpty() || durationMs < 1_000) return null
        return Recorded(data, ((durationMs + 999) / 1000).toInt().coerceAtLeast(1))
    }

    // отмена: выбрасываем записанное
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        val file = recordFile
        recordFile = null
        try {
            r.stop()
        } catch (_: Exception) {
        }
        r.release()
        file?.delete()
    }
}

// Плеер голосовых: состояние в StateFlow, позиция тикает, перемотка (seek
// по доле), скорость ×1/×1.5/×2, системный аудиофокус. Воспроизведение —
// из памяти, расшифрованных временных файлов не остаётся.
class VoicePlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    // AK2: разрешает прослушивание у уха — настройка из AppPrefs
    private val earEnabled: () -> Boolean = { true },
) {

    data class State(
        val playing: Boolean = false,
        val mediaId: String? = null,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val speed: Float = 1f,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // Фаза AB3: колбэки для VM — пауза (позиция для продолжения) и
    // завершение дорожки (прослушано до конца)
    var onPaused: ((mediaId: String, positionMs: Int) -> Unit)? = null
    var onCompleted: ((mediaId: String) -> Unit)? = null

    private var player: MediaPlayer? = null
    private var tickerJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false

    // AK2 (аналог EarListener у Telegram): близость — маршрут в ухо
    private val ear = EarAudio(context) { near -> setEarpiece(near) }

    // AK9 (аналог MediaController у Telegram): кнопки гарнитуры —
    // play/pause и «следующее» (цепочка голосовых)
    var onHeadsetNext: (() -> Unit)? = null
    private var mediaSession: MediaSession? = null

    private fun sessionStart(mediaId: String) {
        if (Build.VERSION.SDK_INT < 21) return
        try {
            val ms = MediaSession(context, "voice")
            ms.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (_state.value.mediaId != null) resume()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    onHeadsetNext?.invoke()
                }
            })
            ms.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(
                        android.media.MediaMetadata.METADATA_KEY_TITLE,
                        context.getString(R.string.voice_message),
                    )
                    .putLong(
                        android.media.MediaMetadata.METADATA_KEY_DURATION,
                        _state.value.durationMs.toLong(),
                    )
                    .build(),
            )
            ms.isActive = true
            mediaSession = ms
            sessionState(mediaId, playing = true, positionMs = _state.value.positionMs, speed = _state.value.speed)
        } catch (_: Exception) {
        }
    }

    private fun sessionState(mediaId: String?, playing: Boolean, positionMs: Int, speed: Float) {
        val ms = mediaSession ?: return
        if (mediaId == null) return
        try {
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT,
                    )
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        positionMs.toLong(),
                        speed,
                    )
                    .build(),
            )
        } catch (_: Exception) {
        }
    }

    private fun sessionStop() {
        val ms = mediaSession ?: return
        mediaSession = null
        try {
            ms.isActive = false
            ms.release()
        } catch (_: Exception) {
        }
    }

    private fun setEarpiece(near: Boolean) {
        val am = audioManager ?: return
        try {
            // MODE_IN_COMMUNICATION без спикера ведёт медиа в наушник уха;
            // NORMAL возвращает обычный маршрут, BT не ломаем принудительно
            am.mode = if (near) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    // потеря фокуса (звонок, другое приложение) — пауза
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    // тап: играем (загрузив при необходимости) либо ставим на паузу.
    // resumeAtMs — стартовая позиция для новой дорожки (AB3: продолжение
    // прерванного прослушивания с сохранённого места)
    fun toggle(mediaId: String, resumeAtMs: Int = 0, load: suspend () -> ByteArray) {
        if (_state.value.mediaId == mediaId && player != null) {
            if (_state.value.playing) pause() else resume()
            return
        }
        loadAndPlay(mediaId, load, resumeAtMs)
    }

    private fun loadAndPlay(mediaId: String, load: suspend () -> ByteArray, resumeAtMs: Int = 0) {
        if (_state.value.loading) return
        // старый плеер и его тикер гасим ДО смены mediaId (B2): иначе тикер
        // прежней дорожки пишет positionMs в состояние новой, пока грузится
        stopPlayback()
        _state.value = _state.value.copy(loading = true, error = null, mediaId = mediaId, positionMs = 0, durationMs = 0)
        scope.launch {
            try {
                val bytes = load()
                stopPlayback()
                val p = MediaPlayer()
                try {
                    p.setDataSource(InMemoryMediaDataSource(bytes))
                    p.setOnCompletionListener {
                        _state.value = _state.value.copy(playing = false, positionMs = 0)
                        tickerJob?.cancel()
                        abandonFocus() // фокус отдаём по окончании (A24)
                        // AB3: дорожка прослушана до конца
                        onCompleted?.invoke(mediaId)
                    }
                    p.prepare()
                } catch (e: Exception) {
                    p.release()
                    throw e
                }
                player = p
                val duration = try {
                    maxOf(1000, p.duration)
                } catch (_: Exception) {
                    1000
                }
                // AB3: продолжение прерванного — seek до start()
                val startAt = resumeAtMs.coerceIn(0, duration)
                if (startAt > 0) {
                    try {
                        p.seekTo(startAt)
                    } catch (_: Exception) {
                    }
                }
                requestFocus()
                p.start()
                // скорость строго после start(): на API 23–27 setter
                // playbackParams может сам запустить воспроизведение
                applySpeed(p)
                _state.value = _state.value.copy(loading = false, playing = true, positionMs = startAt, durationMs = duration)
                startTicker()
                // AK2: слушаем датчик, пока играет
                if (earEnabled()) ear.attach()
                // AK9: сессия для кнопок гарнитуры и экрана блокировки
                sessionStart(mediaId)
            } catch (_: Exception) {
                // гасим и прошлый плеер, если он ещё играл
                stopPlayback()
                _state.value = _state.value.copy(loading = false, playing = false, error = "media_play_error")
            }
        }
    }

    private fun resume() {
        val p = player ?: return
        try {
            p.start()
            applySpeed(p) // скорость после start(), см. loadAndPlay
            _state.value = _state.value.copy(playing = true)
            startTicker()
            // AK2: продолжение — датчик снова слушает
            if (earEnabled()) ear.attach()
            // AK9: гарнитура видит продолжение
            sessionState(_state.value.mediaId, playing = true, positionMs = _state.value.positionMs, speed = _state.value.speed)
        } catch (_: Exception) {
        }
    }

    fun pause() {
        val p = player ?: return
        try {
            p.pause()
        } catch (_: Exception) {
        }
        // AK2: пауза — маршрут и датчик возвращаем
        ear.detach()
        val before = _state.value
        _state.value = before.copy(playing = false)
        // AK9: гарнитура видит паузу
        sessionState(before.mediaId, playing = false, positionMs = before.positionMs, speed = before.speed)
        tickerJob?.cancel()
        abandonFocus() // пауза освобождает фокус, пока не играет (A24)
        // AB3: VM сохраняет позицию — продолжим с места
        if (before.mediaId != null) onPaused?.invoke(before.mediaId, before.positionMs)
    }

    // перемотка по доле [0..1]
    fun seekTo(fraction: Float) {
        val p = player ?: return
        val duration = _state.value.durationMs
        if (duration <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * duration).toInt()
        try {
            p.seekTo(target)
            _state.value = _state.value.copy(positionMs = target)
        } catch (_: Exception) {
        }
    }

    // циклическое переключение скорости ×1 → ×1.5 → ×2
    fun cycleSpeed() {
        val next = when (_state.value.speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        _state.value = _state.value.copy(speed = next)
        val p = player ?: return
        // на паузе не применяем: setter мог бы сам запустить плеер
        // (API 23–27), скорость подхватит resume() после start()
        if (_state.value.playing) applySpeed(p)
    }

    private fun applySpeed(p: MediaPlayer) {
        try {
            p.playbackParams = p.playbackParams.setSpeed(_state.value.speed)
        } catch (_: Exception) {
        }
    }

    // ─── аудиофокус ───

    // фокус на время воспроизведения: медиа-канал, содержимое — речь
    private fun requestFocus() {
        val am = audioManager ?: return
        if (focusHeld) return
        focusHeld = true
        if (Build.VERSION.SDK_INT >= 26) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (!focusHeld) return
        focusHeld = false
        if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest
            focusRequest = null
            if (req != null) am.abandonAudioFocusRequest(req)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(200)
                val p = player ?: break
                try {
                    _state.value = _state.value.copy(positionMs = p.currentPosition)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun stopPlayback() {
        tickerJob?.cancel()
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        ear.detach() // AK2: маршрут из уха вернуть
        sessionStop() // AK9: сессию гарнитуры закрыть
        abandonFocus()
    }

    // уход с экрана — глушим плеер
    fun release() {
        stopPlayback()
        _state.value = State()
    }
}
