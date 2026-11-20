package chat.client.domain

import java.time.Instant
import java.time.ZoneId

// Группировка медиа по месяцам (Фаза AF7, по образцу календаря в
// shared media у Telegram): сетка медиа-таба получает разделители
// месяцев и цель прыжка «перейти к месяцу». ts — секунды эпохи,
// зона — параметром (чистая логика, тесты на JVM).
object MediaMonths {
    data class Month(val epochMonth: Long, val firstIndex: Int, val count: Int)

    // месяц как число: year*12 + (monthValue-1)
    fun epochMonthOf(tsSec: Long, zone: ZoneId): Long =
        Instant.ofEpochSecond(tsSec).atZone(zone).let { it.year.toLong() * 12 + (it.monthValue - 1) }

    // группировка по порядку списка: список должен быть возрастающим по ts;
    // на каждый новый месяц — запись с firstIndex = индекс первого элемента
    // месяца в исходном списке и count = элементов месяца
    fun group(tsList: List<Long>, zone: ZoneId): List<Month> {
        if (tsList.isEmpty()) return emptyList()
        val out = ArrayList<Month>()
        // текущий месяц и его накопленное число элементов
        var em = epochMonthOf(tsList[0], zone)
        var count = 1
        for (i in 1 until tsList.size) {
            val cur = epochMonthOf(tsList[i], zone)
            if (cur != em) {
                // месяц сменился: закрываем прежний, его первый индекс = i - count
                out.add(Month(em, i - count, count))
                em = cur
                count = 1
            } else {
                count++
            }
        }
        // последний месяц: первый индекс = размер списка минус его длина
        out.add(Month(em, tsList.size - count, count))
        return out
    }
}
