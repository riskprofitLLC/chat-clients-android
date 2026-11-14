package chat.client.domain

// Дедупликация входящих: LRU-набор ключей. Один и тот же блоб дважды
// (повтор transfer, ретрай доставки) не должен плодить записи в истории.
// seen — проверка БЕЗ запоминания: карантин/отклонённые блобы не
// засоряют индекс (C4), реплей после accept идёт как первая обработка
class DedupIndex(private val capacity: Int = 1024) {

    private val seenMap = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean = size > capacity
    }

    @Synchronized
    fun remember(key: String): Boolean {
        val known = seenMap.containsKey(key)
        seenMap[key] = Unit
        return known
    }

    // уже видели такой ключ? НЕ запоминает
    @Synchronized
    fun seen(key: String): Boolean = seenMap.containsKey(key)

    @Synchronized
    fun clear() = seenMap.clear()
}
