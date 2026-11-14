package chat.client.data

import chat.crypto.MessageRecord
import chat.crypto.MessageStore

// Сериализованный доступ к локальному журналу: AndroidMessageStore не
// потокобезопасен, а пишут одновременно дрейн, отправка и тикеры.
// Реализует MessageStore (передаётся в Backup), плюс строго монотонный
// ts в пределах чата: несколько сообщений одного дрейна не получают
// одну метку времени.
class StoreGate(private var store: MessageStore) : MessageStore {

    private val lock = Any()
    private val lastTs = HashMap<String, Long>()

    // поколение записи по чату (C1): растёт на каждый append/delete —
    // дешёвая подпись для кэша сводок. Глобальная эпоха не сбрасывается
    // при reopen (A9): после wipe подписи нового содержимого гарантированно
    // не совпадают со старыми — устаревший кэш не воскреснет
    private val generations = HashMap<String, Long>()
    @Volatile private var epoch: Long = 0L

    // реактивные изменения журнала (C1): UI может наблюдать чат без
    // ручной инвалидации — StoreGate единственная точка записи (A12)
    private val _chatChanges = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 256)
    val chatChanges: kotlinx.coroutines.flow.SharedFlow<String> = _chatChanges

    private fun markChanged(chatId: String) {
        // инкремент ПОД блокировкой (B9): раньше писали в generations
        // вне lock — гонка с generation() давала неповторимые подписи
        synchronized(lock) {
            generations[chatId] = (generations[chatId] ?: 0L) + 1
        }
        _chatChanges.tryEmit(chatId)
    }

    // подпись включает эпоху: коллизия с доре-open значением исключена
    fun generation(chatId: String): Long = synchronized(lock) {
        (generations[chatId] ?: 0L) + epoch
    }

    // переоткрытие после wipe каталога (A6): синглтон держал открытый
    // журнал удалённого каталога, запись шла бы в пустоту/остатки кэша.
    // A9: эпоха уходит вверх — подписи и кэши сводок/поиска прежнего
    // содержимого никогда не совпадут с новыми
    fun reopen(newStore: MessageStore) = synchronized(lock) {
        store = newStore
        lastTs.clear()
        epoch += 1_000_000_000L // новый диапазон подписей целиком
        generations.clear()
    }

    // добавление с монотонным ts: max(подсказка, текущее время, last+1).
    // Подсказка — момент ДО отправки: кадр delivered успевает прийти
    // раньше записи в журнал (A16)
    fun appendMonotonic(chatId: String, from: String, content: ByteArray, tsHint: Long = 0L): MessageRecord = synchronized(lock) {
        val now = System.currentTimeMillis()
        val base = maxOf(now, tsHint)
        val ts = nextTs(chatId, base)
        val record = MessageRecord(from, ts, content)
        store.append(chatId, record)
        record
    }.also { markChanged(chatId) }

    // сохранение записи как есть (transfer/backup: ts приходит из файла)
    override fun append(chatId: String, record: MessageRecord): Unit = synchronized(lock) {
        store.append(chatId, record)
        val last = lastTs[chatId] ?: 0L
        if (record.timestamp > last) lastTs[chatId] = record.timestamp
    }.also { markChanged(chatId) }

    override fun history(chatId: String, limit: Int): List<MessageRecord> = synchronized(lock) {
        store.history(chatId, limit)
    }

    override fun chatIds(): List<String> = synchronized(lock) { store.chatIds() }

    override fun deleteRecord(chatId: String, timestamp: Long, content: ByteArray): Unit = synchronized(lock) {
        store.deleteRecord(chatId, timestamp, content)
    }.also { markChanged(chatId) }

    // K3: удаление по предикату — один проход стора и одна перезапись
    // файла (прежде — N полных расшифровок и N атомарных rewrite)
    override fun deleteRecords(chatId: String, predicate: (MessageRecord) -> Boolean): Int = synchronized(lock) {
        store.deleteRecords(chatId, predicate)
    }.also { if (it > 0) markChanged(chatId) }

    // K3: удаление списка записей — обёртка над предикатом (сравнение по
    // ts и байтам контента), тоже одна перезапись
    fun deleteRecords(chatId: String, records: List<MessageRecord>): Int {
        val byTs = HashMap<Long, MutableList<ByteArray>>()
        for (record in records) byTs.getOrPut(record.timestamp) { mutableListOf() }.add(record.content)
        return deleteRecords(chatId) { record ->
            byTs[record.timestamp]?.any { it.contentEquals(record.content) } == true
        }
    }

    fun lastTs(chatId: String): Long {
        synchronized(lock) {
            lastTs[chatId]?.let { return it }
        }
        // первый заход: максимум из хвоста истории
        val max = synchronized(lock) { store.history(chatId, 100).maxOfOrNull { it.timestamp } ?: 0L }
        synchronized(lock) { lastTs[chatId] = max }
        return max
    }

    private fun nextTs(chatId: String, hint: Long = 0L): Long {
        val now = System.currentTimeMillis()
        val last = lastTs[chatId] ?: run {
            val tail = synchronized(lock) { store.history(chatId, 100).maxOfOrNull { it.timestamp } ?: 0L }
            lastTs[chatId] = tail
            tail
        }
        val base = maxOf(now, hint)
        val ts = if (base > last) base else last + 1
        lastTs[chatId] = ts
        return ts
    }
}
