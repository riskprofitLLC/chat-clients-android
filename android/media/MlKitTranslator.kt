package chat.client.media

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// GE3: on-device перевод через ML Kit (модель скачивается один раз через
// Play services, далее offline; текст не покидает устройство).
// Translator кэшируется по языку.
class MlKitTranslator(private val context: Context) {

    private val cache = HashMap<String, com.google.mlkit.nl.translate.Translator>()

    private fun translatorFor(targetLang: String): com.google.mlkit.nl.translate.Translator {
        cache[targetLang]?.let { return it }
        // без source-языка ML Kit определяет источник автоматически
        val options = TranslatorOptions.Builder().setTargetLanguage(targetLang).build()
        return com.google.mlkit.nl.translate.Translation.getClient(options).also { cache[targetLang] = it }
    }

    suspend fun translate(text: String, targetLang: String): String = withContext(Dispatchers.IO) {
        val tr = translatorFor(targetLang)
        // модель должна быть скачана: ждём скач (offline после первого раза)
        Tasks.await(tr.downloadModelIfNeeded())
        Tasks.await(tr.translate(text))
    }

    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}
