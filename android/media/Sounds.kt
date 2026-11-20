package chat.client.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import chat.client.domain.SoundPolicy

// Звуки в приложении (по образцу Telegram): короткий «тик» на отправку,
// «дин-дон» на входящее в открытом приложении. SoundPool с ленивой
// загрузкой, троттлинг и уважение к беззвучному режиму — в SoundPolicy
class Sounds(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var pool: SoundPool? = null
    private var inId = 0
    private var outId = 0
    private var lastInAt = 0L
    private var lastOutAt = 0L

    // входящее новое сообщение в открытом приложении; silent (Фаза X1) —
    // отправитель попросил без звука: у получателя тишина
    @Synchronized
    fun playIncoming(enabled: Boolean, chatMuted: Boolean, silent: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!SoundPolicy.shouldPlayIncoming(enabled, chatMuted, ringerSilent(), now - lastInAt, silent)) return
        lastInAt = now
        ensurePool()
        if (inId != 0) tryPlay(inId)
    }

    // отправка сообщения
    @Synchronized
    fun playOutgoing(enabled: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!SoundPolicy.shouldPlayOutgoing(enabled, ringerSilent(), now - lastOutAt)) return
        lastOutAt = now
        ensurePool()
        if (outId != 0) tryPlay(outId)
    }

    private fun ringerSilent(): Boolean = try {
        audioManager?.ringerMode == AudioManager.RINGER_MODE_SILENT
    } catch (_: Exception) {
        false
    }

    // ленивая инициализация: звук не грузится, пока не нужен
    private fun ensurePool() {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
        inId = p.load(context, chat.client.R.raw.snd_in, 1)
        outId = p.load(context, chat.client.R.raw.snd_out, 1)
        pool = p
    }

    private fun tryPlay(sampleId: Int) {
        try {
            pool?.play(sampleId, 1f, 1f, 1, 0, 1f)
        } catch (_: Exception) {
        }
    }
}
