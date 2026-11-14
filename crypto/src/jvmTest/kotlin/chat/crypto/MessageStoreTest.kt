package chat.crypto

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageStoreTest {
    private lateinit var dir: java.io.File
    private val key = CryptoPrimitives.randomBytes(32)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("chat-store").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun appendHistoryRoundTrip() {
        val store = JournalMessageStore(dir, key)
        store.append("alice", MessageRecord("alice", 1000L, "первое".encodeToByteArray()))
        store.append("alice", MessageRecord("bob", 2000L, "второе".encodeToByteArray()))
        val history = store.history("alice")
        assertEquals(2, history.size)
        assertEquals("alice", history[0].from)
        assertEquals(1000L, history[0].timestamp)
        assertContentEquals("первое".encodeToByteArray(), history[0].content)
        assertEquals("bob", history[1].from)
        assertContentEquals("второе".encodeToByteArray(), history[1].content)
    }

    @Test
    fun plaintextNeverOnDisk() {
        val store = JournalMessageStore(dir, key)
        val secret = "секретная-фраза-не-должна-утечь".encodeToByteArray()
        store.append("top", MessageRecord("alice", 1L, secret))
        val raw = java.io.File(dir, "chat-top.log").readText()
        assertTrue(!raw.contains("секретная"), "plaintext не должен попадать на диск")
        assertTrue(!raw.contains("alice"), "отправитель не должен читаться с диска")
    }

    @Test
    fun persistsAcrossReopen() {
        JournalMessageStore(dir, key).append("chat", MessageRecord("u", 42L, "перезапуск".encodeToByteArray()))
        val reopened = JournalMessageStore(dir, key)
        val history = reopened.history("chat")
        assertEquals(1, history.size)
        assertContentEquals("перезапуск".encodeToByteArray(), history[0].content)
    }

    @Test
    fun wrongKeyFails() {
        JournalMessageStore(dir, key).append("chat", MessageRecord("u", 1L, "x".encodeToByteArray()))
        val stranger = JournalMessageStore(dir, CryptoPrimitives.randomBytes(32))
        assertFailsWith<Exception> { stranger.history("chat") }
    }

    @Test
    fun limitReturnsTail() {
        val store = JournalMessageStore(dir, key)
        repeat(5) { i ->
            store.append("chat", MessageRecord("u", i.toLong(), byteArrayOf(i.toByte())))
        }
        val tail = store.history("chat", limit = 3)
        assertEquals(listOf(2L, 3L, 4L), tail.map { it.timestamp })
    }

    @Test
    fun aadBindsChatId() {
        val store = JournalMessageStore(dir, key)
        store.append("a", MessageRecord("u", 1L, "x".encodeToByteArray()))
        // подмена чата: запись переезжает в другой файл, AAD не совпадёт
        java.io.File(dir, "chat-a.log").renameTo(java.io.File(dir, "chat-b.log"))
        assertFailsWith<Exception> { store.history("b") }
    }

    @Test
    fun chatIdValidationAndListing() {
        val store = JournalMessageStore(dir, key)
        assertFailsWith<Exception> { store.append("../escape", MessageRecord("u", 1L, ByteArray(0))) }
        assertFailsWith<Exception> { store.append("", MessageRecord("u", 1L, ByteArray(0))) }
        store.append("beta", MessageRecord("u", 1L, ByteArray(0)))
        store.append("alpha", MessageRecord("u", 1L, ByteArray(0)))
        assertEquals(listOf("alpha", "beta"), store.chatIds())
    }

    @Test
    fun physicallyCorruptLineIsSkipped() {
        // сбой питания посреди записи: хвост из нулей/битые байты не должны
        // валить приложение — остальная история читается (fix Фазы U)
        val store = JournalMessageStore(dir, key)
        store.append("chat", MessageRecord("u", 1L, "первое".encodeToByteArray()))
        store.append("chat", MessageRecord("u", 2L, "второе".encodeToByteArray()))
        java.io.File(dir, "chat-chat.log").appendBytes(ByteArray(337) + "\n".encodeToByteArray())
        java.io.File(dir, "chat-chat.log").appendText("!!!НЕ-БАЗА64!!!\n")
        val history = store.history("chat")
        assertEquals(2, history.size)
        assertEquals(listOf(1L, 2L), history.map { it.timestamp })
    }

    // K2: удаление в чате с повреждённым хвостом не валится и не теряет
    // здоровые записи; битые строки сохраняются как есть
    @Test
    fun deleteRecordSurvivesCorruptedTail() {
        val store = JournalMessageStore(dir, key)
        store.append("chat", MessageRecord("u", 1L, "первое".encodeToByteArray()))
        store.append("chat", MessageRecord("u", 2L, "цель".encodeToByteArray()))
        store.append("chat", MessageRecord("u", 3L, "третье".encodeToByteArray()))
        val log = java.io.File(dir, "chat-chat.log")
        log.appendBytes(ByteArray(337) + "\n".encodeToByteArray())
        log.appendText("!!!НЕ-БАЗА64!!!\n")

        // прежде этот вызов падал на decode битой строки
        store.deleteRecord("chat", 2L, "цель".encodeToByteArray())

        val history = store.history("chat")
        assertEquals(listOf(1L, 3L), history.map { it.timestamp })
        // повреждённые строки не выброшены из файла
        val text = log.readText()
        assertTrue(text.contains("НЕ-БАЗА64"), "битая строка base64 обязана сохраниться")
        assertTrue(text.count { it == '\u0000' } >= 300, "хвост из нулей обязан сохраниться")
    }

    // K1: обрыв посреди перезаписи (rename не удался) оставляет журнал целым
    @Test
    fun deleteRecordKeepsHistoryWhenRenameFails() {
        val store = JournalMessageStore(dir, key)
        store.append("chat", MessageRecord("u", 1L, "первое".encodeToByteArray()))
        store.append("chat", MessageRecord("u", 2L, "цель".encodeToByteArray()))

        // «обрыв»: rename невозможен — tmp-путь занят каталогом
        val log = java.io.File(dir, "chat-chat.log")
        val tmp = java.io.File(dir, "chat-chat.log.tmp")
        tmp.mkdirs()

        assertFailsWith<Exception> { store.deleteRecord("chat", 2L, "цель".encodeToByteArray()) }

        // исходный журнал цел: обе записи на месте, ничего не потеряно
        assertEquals(listOf(1L, 2L), store.history("chat").map { it.timestamp })
        tmp.deleteRecursively()
    }

    // K1: успешное удаление не оставляет мусорных .tmp
    @Test
    fun deleteRecordLeavesNoTmpBehind() {
        val store = JournalMessageStore(dir, key)
        store.append("chat", MessageRecord("u", 1L, "первое".encodeToByteArray()))
        store.append("chat", MessageRecord("u", 2L, "цель".encodeToByteArray()))
        store.deleteRecord("chat", 2L, "цель".encodeToByteArray())
        assertEquals(listOf(1L), store.history("chat").map { it.timestamp })
        assertTrue(java.io.File(dir, "chat-chat.log.tmp").exists().not(), "tmp не должен оставаться")
    }

    // K1: осиротевший .tmp от обрыва убирается при следующем открытии
    @Test
    fun leftoverTmpCleanedOnReopen() {
        val store = JournalMessageStore(dir, key)
        store.append("chat", MessageRecord("u", 1L, "запись".encodeToByteArray()))
        java.io.File(dir, "chat-chat.log.tmp").writeBytes("мусор от обрыва".encodeToByteArray())

        val reopened = JournalMessageStore(dir, key)
        assertTrue(java.io.File(dir, "chat-chat.log.tmp").exists().not(), "осиротевший tmp должен исчезнуть")
        assertEquals(1, reopened.history("chat").size)
    }

    // K3: пакетное удаление за один проход — счёт, содержимое и битые строки
    @Test
    fun deleteRecordsRemovesBatchAtOnce() {
        val store = JournalMessageStore(dir, key)
        for (i in 1..50) {
            store.append("chat", MessageRecord("u", i.toLong(), "запись-$i".encodeToByteArray()))
        }
        val log = java.io.File(dir, "chat-chat.log")
        log.appendText("!!!НЕ-БАЗА64!!!\n")

        // удаляем первую половину предикатом — одним вызовом
        val removed = store.deleteRecords("chat") { it.timestamp <= 25L }
        assertEquals(25, removed)
        val history = store.history("chat")
        assertEquals(25, history.size)
        assertEquals(26L, history.first().timestamp)
        // битая строка сохранена (K2)
        assertTrue(log.readText().contains("НЕ-БАЗА64"))
        // повторный проход по тому же предикату ничего не находит
        assertEquals(0, store.deleteRecords("chat") { it.timestamp <= 25L })
    }

    // ─── Раунд 7 ───

    // 1.3: обрыв посреди append оставляет строку без \n — следующая запись
    // приклеивалась к обрывку и чат терял читаемость; первый append нового
    // процесса дописывает недостающий перевод строки и запись читается
    @Test
    fun appendAfterTruncatedTailIsReadable() {
        val store = JournalMessageStore(dir, key)
        store.append("chat", MessageRecord("u", 1L, "первое".encodeToByteArray()))
        val file = java.io.File(dir, "chat-chat.log")
        // сбой питания: файл остался без завершающего \n
        val raw = file.readBytes()
        file.writeBytes(raw.copyOfRange(0, raw.size - 1))
        // новый процесс (новый инстанс стора) дописывает запись
        val reopened = JournalMessageStore(dir, key)
        reopened.append("chat", MessageRecord("u", 2L, "второе".encodeToByteArray()))
        val history = reopened.history("chat")
        assertEquals(
            expected = listOf("первое", "второе"),
            actual = history.map { it.content.decodeToString() },
            message = "новая запись обязана читаться, а не приклеиться к обрывку",
        )
    }

    // 4.1: history(limit) возвращает последние limit СТРОК; битые строки
    // хвоста пропускаются (занимая слот) и не валят чтение
    @Test
    fun historyLimitSkipsCorruptTailLines() {
        val store = JournalMessageStore(dir, key)
        repeat(30) { i ->
            store.append("chat", MessageRecord("u", i.toLong(), "текст-$i".encodeToByteArray()))
        }
        val file = java.io.File(dir, "chat-chat.log")
        file.appendText("!!!НЕ-БАЗА64!!!\n") // порча в хвосте
        val tail = store.history("chat", limit = 5)
        // 5 строк = [26, 27, 28, 29, порча] — валидных четыре
        assertEquals(listOf(26L, 27L, 28L, 29L), tail.map { it.timestamp })
    }

    // 4.1: хвост большого журнала читается быстро — расшифровываются
    // только последние limit строк, а не весь файл
    @Test
    fun historyLimitIsFastOnLargeJournal() {
        val store = JournalMessageStore(dir, key)
        repeat(5000) { i ->
            store.append("big", MessageRecord("u", i.toLong(), ByteArray(64) { it.toByte() }))
        }
        val tailStart = System.nanoTime()
        val tail = store.history("big", limit = 20)
        val tailMs = (System.nanoTime() - tailStart) / 1_000_000
        assertEquals((4980..4999).map { it.toLong() }, tail.map { it.timestamp })
        val fullStart = System.nanoTime()
        store.history("big", limit = Int.MAX_VALUE)
        val fullMs = (System.nanoTime() - fullStart) / 1_000_000
        // хвост обязан быть существенно быстрее полного разбора; запас
        // большой, чтобы не флапать на медленных машинах
        assertTrue(
            tailMs < 200 && tailMs <= fullMs / 2,
            "хвост $tailMs мс против полного $fullMs мс — хвост обязан быть быстрее",
        )
    }
}
