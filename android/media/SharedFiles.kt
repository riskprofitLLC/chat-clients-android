package chat.client.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

// Раздача расшифрованных копий медиа внешним приложениям (плеер, просмотрщик)
// через FileProvider. Копия живёт в cacheDir/shared — чистится при старте
// приложения (Media.cleanTemp) и после использования по возможности.
object SharedFiles {

    private fun sharedDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    // расшифрованная копия для внешнего приложения; null — медиа недоступно
    fun exportCopy(context: Context, fileName: String, bytes: ByteArray): File {
        val dir = sharedDir(context)
        // уникальный префикс, чтобы не затирать чужие копии
        val safe = File(dir, "${System.nanoTime()}_${fileName.replace(Regex("""[/\\]"""), "_")}")
        safe.writeBytes(bytes)
        return safe
    }

    // чистим все расшаренные копии (кэш растёт от каждого просмотра)
    fun cleanupShared(context: Context) {
        File(context.cacheDir, "shared").deleteRecursively()
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    // ACTION_VIEW для видео/аудио/документов
    fun viewIntent(context: Context, file: File, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // ACTION_SEND для «Поделиться» наружу (Фаза X3, аналог share у Telegram):
    // медиа — стримом через FileProvider, текст — EXTRA_TEXT
    fun shareIntent(context: Context, file: File, mime: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // шар текста без файла (Фаза X3)
    fun shareTextIntent(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
