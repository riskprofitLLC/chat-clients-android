package chat.client.domain

// Автозагрузка транзитного медиа (Фаза X5, аналог Data Saver /
// автозагрузки по типу сети у Telegram): фоновая докачка вложений
// после дрейна ограничена сетью — Wi-Fi или любая. Просмотр медиа
// по тапу качает всегда, ограничение — только про «само качается».
enum class MediaAutoDownload(val key: String) {
    ALWAYS("always"), // качать в любой сети
    WIFI("wifi"), // только безлимитные сети (Wi-Fi)
    NEVER("never"); // не качать автоматически — только по тапу

    companion object {
        // парсинг настройки; мусор и null — прежний режим «всегда»
        fun parse(raw: String?): MediaAutoDownload =
            entries.firstOrNull { it.key == raw } ?: ALWAYS
    }
}

// чистая политика: качать ли медиа в фоне при текущей сети
object MediaAutoDownloadPolicy {

    // metered — платная/лимитная сеть (ConnectivityManager.isActiveNetworkMetered)
    fun shouldAutoDownload(mode: MediaAutoDownload, metered: Boolean): Boolean = when (mode) {
        MediaAutoDownload.ALWAYS -> true
        MediaAutoDownload.WIFI -> !metered
        MediaAutoDownload.NEVER -> false
    }

    // ─── AI8 (аналог матрицы Auto-Download у Telegram): политика
    // по типам вложений ───

    // тип вложения по дескриптору: голосовое/фото/видео/файл
    fun kindOf(info: MediaInfo): MediaAutoKind = when {
        info.type == "voice" -> MediaAutoKind.VOICE
        info.type == "media" -> MediaAutoKind.PHOTO
        info.mime?.startsWith("video/") == true || info.videoNote -> MediaAutoKind.VIDEO
        else -> MediaAutoKind.FILE
    }

    // политика типа: незаданный наследует глобальную
    fun modeOf(kind: MediaAutoKind, perType: Map<MediaAutoKind, MediaAutoDownload>, fallback: MediaAutoDownload): MediaAutoDownload =
        perType[kind] ?: fallback

    // разбор сохранённых настроек: ключи типов, мусор — нет записи
    fun parseTypes(raw: Map<String, String?>): Map<MediaAutoKind, MediaAutoDownload> {
        val out = LinkedHashMap<MediaAutoKind, MediaAutoDownload>()
        for (kind in MediaAutoKind.entries) {
            val mode = MediaAutoDownload.parse(raw["media_autodl_${kind.key}"])
            if (raw["media_autodl_${kind.key}"] != null) out[kind] = mode
        }
        return out
    }
}

// AI8: типы вложений для матрицы автозагрузки
enum class MediaAutoKind(val key: String, val prefKey: String) {
    PHOTO("photo", "photo"),
    VIDEO("video", "video"),
    FILE("file", "file"),
    VOICE("voice", "voice"),
}

// AK3 (аналог лимита размера автозагрузки из Data Settings у Telegram):
// фоновая докачка не стартует для вложений больше потолка. 0 — без
// лимита (прежде поведение); просмотр по тапу лимит не трогает.
object MediaAutoCap {

    const val KB_UNLIMITED = 0
    val CHOICES_KB = listOf(0, 512, 1024, 5120)

    fun parse(raw: Int?): Int =
        if (raw != null && raw >= 0) CHOICES_KB.firstOrNull { it == raw } ?: KB_UNLIMITED else KB_UNLIMITED

    // вписывается ли вложение в потолок: без лимита — да
    fun within(capKb: Int, bytes: Long): Boolean {
        if (capKb <= KB_UNLIMITED) return true
        if (bytes < 0) return true // размер неизвестен — не мешаем
        return bytes <= capKb * 1024L
    }
}
