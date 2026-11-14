package chat.client.domain

// AL2 (аналог диапазона дат в экспорте Telegram): выгрузка истории
// не только целиком — за период, пресетами «неделя/месяц/год» или
// точным окном. Чистый фильтр по ts записей.
object ExportRange {

    // окно экспорта: [fromMs..toMs] включительно по обеим границам
    class Range(val fromMs: Long, val toMs: Long) {
        fun contains(ts: Long): Boolean = ts in fromMs..toMs
    }

    // пресеты периода; null — всё время (прежнее поведение)
    enum class Preset(val key: String) {
        WEEK("week"),
        MONTH("month"),
        YEAR("year"),
    }

    // окно пресета: от now назад до самого раннего ts
    fun presetRange(preset: Preset?, nowMs: Long): Range? = when (preset) {
        null -> null
        Preset.WEEK -> Range(nowMs - 7L * 24 * 60 * 60 * 1000, Long.MAX_VALUE)
        Preset.MONTH -> Range(nowMs - 30L * 24 * 60 * 60 * 1000, Long.MAX_VALUE)
        Preset.YEAR -> Range(nowMs - 365L * 24 * 60 * 60 * 1000, Long.MAX_VALUE)
    }

    // разбор выбора из UI: null/мусор — без фильтра
    fun presetFromKey(raw: String?): Preset? =
        raw?.let { r -> Preset.entries.firstOrNull { it.key == r } }

    // точное окно: обе границы опциональны, порядок чиним сами
    fun exactRange(fromMs: Long?, toMs: Long?): Range {
        val f = fromMs ?: 0L
        val t = toMs ?: Long.MAX_VALUE
        return Range(minOf(f, t), maxOf(f, t))
    }

    // фильтр записей экспорта по окну; null — без фильтра
    fun <T> filter(entries: List<T>, range: Range?, tsOf: (T) -> Long): List<T> =
        if (range == null) entries else entries.filter { range.contains(tsOf(it)) }
}
