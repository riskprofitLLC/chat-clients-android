package chat.client.domain

// Медленный режим группы (Фаза AC4, аналог slow mode у Telegram):
// участники пишут не чаще одного раза за период. Периоды — как у
// Telegram (10с/30с/1м/5м/15м/1ч); 0 — выключен. Гейт — клиентский,
// период едет в групповом конверте (поле "sm", по образцу "bc") и
// применяется ревизионно. Чистая логика — тестируется на JVM.
object SlowMode {

    // допустимые периоды, секунд; 0 — выключен
    val PERIODS = listOf(0, 10, 30, 60, 300, 900, 3600)

    fun validPeriod(sec: Int): Boolean = sec in PERIODS

    // мусорный период читается как выключенный (строгий отброс ронял бы
    // само сообщение, а режим — не критично для доставки текста)
    fun sanitize(sec: Int): Int = if (validPeriod(sec)) sec else 0

    // сколько осталось ждать до следующей отправки, сек;
    // null — можно отправлять (период выключен или уже истёк)
    fun remainingSec(lastOwnTs: Long, now: Long, periodSec: Int): Int? {
        if (periodSec <= 0 || lastOwnTs <= 0) return null
        val passedSec = (now - lastOwnTs) / 1000
        val left = periodSec - passedSec
        return if (left > 0) left.toInt() else null
    }

    // ts последнего СВОЕГО сообщения в группе — гейт считает от него;
    // список возрастающий, ищем с конца
    fun lastOwnTs(messages: List<Pair<Long, String>>, myLogin: String): Long =
        messages.lastOrNull { it.second == myLogin }?.first ?: 0L
}
