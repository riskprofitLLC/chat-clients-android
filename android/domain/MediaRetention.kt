package chat.client.domain

// Фаза AE1 (аналог «Keep media» у Telegram): политика хранения локальных
// копий медиа. Локальная копия — единственная: на сервере транзит живёт
// 30 дней, поэтому чистка честно предупреждает в настройках. Чистая
// логика без java.io — JVM-тесты; сама зачистка файлов — в Media.
object MediaRetention {

    // 0 — хранить всегда; иначе срок в днях (как у Telegram: 3д/1н/1м)
    val CHOICES: List<Int> = listOf(0, 3, 7, 30, 90)

    fun sanitize(days: Int): Int = if (days in CHOICES) days else 0

    // граница старости: младше неё — живём; Long.MIN — чистить нечего
    fun cutoffMs(nowMs: Long, keepDays: Int): Long {
        val days = sanitize(keepDays)
        return if (days == 0) Long.MIN_VALUE else nowMs - days * 24L * 60 * 60 * 1000
    }

    fun isExpired(lastModifiedMs: Long, cutoffMs: Long): Boolean = lastModifiedMs < cutoffMs

    // элементы под удаление: время последнего изменения — предикатом
    fun <T> expiredOf(items: List<T>, cutoffMs: Long, lastModified: (T) -> Long): List<T> =
        items.filter { isExpired(lastModified(it), cutoffMs) }
}
