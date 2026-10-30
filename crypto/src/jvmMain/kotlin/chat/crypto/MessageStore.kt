package chat.crypto

import java.io.File
import java.util.Base64

// Локальная история: живёт только на устройстве, записи зашифрованы ключом данных.
// Ключ данных хранится в обёртке AndroidKeyStore (см. AndroidMessageStore).
// ponytail: журнал на чат без индексов; при больших историях — SQLite на устройстве.
interface MessageStore {
    fun append(chatId: String, record: MessageRecord)

    fun history(chatId: String, limit: Int = 100): List<MessageRecord>

    fun chatIds(): List<String>

    // удалить запись по метке времени и содержимому (первое совпадение)
    fun deleteRecord(chatId: String, timestamp: Long, content: ByteArray)

    // K3: пакетное удаление по предикату — один проход по журналу и одна
    // атомарная перезапись вместо N перезаписей файла; возвращает счёт
    fun deleteRecords(chatId: String, predicate: (MessageRecord) -> Boolean): Int
}

class MessageRecord(
    val from: String,
    val timestamp: Long,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MessageRecord &&
            other.from == from &&
            other.timestamp == timestamp &&
            other.content.contentEquals(content)

    override fun hashCode(): Int = from.hashCode() * 31 + timestamp.hashCode()
}

// Журнал: файл chat-<id>.log, каждая запись — строка base64(IV || GCM(запись)),
// AAD = chatId. На диске нет ни plaintext, ни имён отправителей.
// Перезапись журнала — только атомарно (tmp + fsync + rename, K1): обрыв
// посреди удаления не уничтожает историю.
class JournalMessageStore(
    dir: File,
    private val dataKey: ByteArray,
) : MessageStore {
    private val chatDir = dir.apply { mkdirs() }
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    init {
        require(dataKey.size == 32) { "ключ данных должен быть 32 байта" }
        // осиротевшие .tmp от обрыва посреди перезаписи: исходный журнал
        // при этом цел, мусор убираем при открытии
        chatDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    // 1.3: журнал чата гарантированно заканчивается \n — флаг кэшируется
    // на чат. Обрыв посреди append оставлял строку без перевода строки,
    // следующая запись приклеивалась к обрывку и чат терял и её, и читаемость
    private val tailOk = HashSet<String>()

    private fun ensureLineEnd(file: File, chatId: String) {
        if (chatId in tailOk) return
        if (file.exists() && file.length() > 0) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(raf.length() - 1)
                if (raf.read().toInt() != '\n'.code) {
                    // обрыв посреди записи: дописываем перевод строки первым —
                    // иначе новая запись приклеится к обрывку
                    file.appendText("\n")
                }
            }
        }
        tailOk += chatId
    }

    override fun append(chatId: String, record: MessageRecord) {
        val iv = CryptoPrimitives.randomBytes(12)
        val sealed = CryptoPrimitives.aesGcmEncrypt(
            dataKey,
            iv,
            serialize(record),
            chatId.encodeToByteArray(),
        )
        val file = chatFile(chatId)
        ensureLineEnd(file, chatId)
        file.appendText(encoder.encodeToString(iv + sealed) + "\n")
    }

    override fun history(chatId: String, limit: Int): List<MessageRecord> {
        val file = chatFile(chatId)
        if (!file.exists() || limit <= 0) return emptyList()
        val records = mutableListOf<MessageRecord>()
        // 4.1: ограниченный limit читает только хвост — последние limit
        // строк; полный разбор журнала ради 20 записей уведомления был
        // главной тяжестью дрейна на больших историях. Битые строки в хвосте
        // пропускаются; чужой ключ по-прежнему даёт видимую ошибку
        val fromOffset = if (limit == Int.MAX_VALUE) 0L else tailOffset(file, limit)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(fromOffset)
            // строки журнала — base64 (ASCII), readLine достаточен
            while (true) {
                val line = raf.readLine() ?: break
                if (line.isBlank()) continue
                // физическая порча строки (сбой питания/жёсткая остановка
                // посреди записи: нули, обрыв) — пропускаем, остальная история
                // жива. Ошибки расшифровки при этом НЕ глотаем: чужой ключ/чужой
                // AAD — это не порча, и она обязана быть видимой
                val record = decodeLine(chatId, line) ?: continue
                records += record
            }
        }
        return if (records.size <= limit) records else records.subList(records.size - limit, records.size)
    }

    // 4.1: смещение начала последних need строк — обратный скан по \n,
    // без чтения всего файла; 0 — строк меньше, читаем с начала (хвост
    // потом обрежется списком)
    private fun tailOffset(file: File, need: Int): Long {
        if (need <= 0) return 0L
        java.io.RandomAccessFile(file, "r").use { raf ->
            var pos = raf.length()
            val buf = ByteArray(64 * 1024)
            var found = 0
            while (pos > 0) {
                val start = maxOf(0L, pos - buf.size)
                raf.seek(start)
                raf.readFully(buf, 0, (pos - start).toInt())
                for (i in (pos - start).toInt() - 1 downTo 0) {
                    if (buf[i] == '\n'.code.toByte()) {
                        if (found == need) return start + i + 1
                        found++
                    }
                }
                pos = start
            }
            return 0L
        }
    }

    override fun chatIds(): List<String> =
        chatDir
            .listFiles { f -> f.name.endsWith(".log") }
            .orEmpty()
            .map { f -> f.nameWithoutExtension.removePrefix("chat-") }
            .sorted()

    override fun deleteRecord(chatId: String, timestamp: Long, content: ByteArray) {
        // K3: одиночное удаление — частный случай пакетного: первый
        // подходящий по (ts, content) и только он
        var matched = false
        deleteRecords(chatId) { record ->
            if (!matched && record.timestamp == timestamp && record.content.contentEquals(content)) {
                matched = true
                true
            } else {
                false
            }
        }
    }

    // K3: удаление всех подходящих записей за один проход — одна расшифровка
    // на строку и одна атомарная перезапись файла (было O(n) перезаписей)
    override fun deleteRecords(chatId: String, predicate: (MessageRecord) -> Boolean): Int {
        val file = chatFile(chatId)
        if (!file.exists()) return 0
        val lines = file.readLines()
        val kept = mutableListOf<String>()
        var removed = 0
        for (line in lines) {
            if (line.isBlank()) continue // пустые строки журналу не нужны
            // повреждённые строки не декодируются, но сохраняются как есть
            // (K2): удалить конкретные записи они не мешают, а выбрасывать
            // чужие байты из-за одного удаления мы не имеем права
            val record = try {
                decodeLine(chatId, line)
            } catch (_: Exception) {
                null
            }
            if (record != null && predicate(record)) {
                removed++
            } else {
                kept.add(line)
            }
        }
        if (removed > 0) {
            rewriteAtomic(file, kept)
        }
        return removed
    }

    // расшифровка одной строки журнала; null — физическая порча (base64/
    // обрыв), как в history: ошибка GCM чужого ключа НЕ глотается
    private fun decodeLine(chatId: String, line: String): MessageRecord? {
        val blob = try {
            decoder.decode(line)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (blob.size < 12 + 16) return null
        val plain = CryptoPrimitives.aesGcmDecrypt(
            dataKey,
            blob.copyOfRange(0, 12),
            blob.copyOfRange(12, blob.size),
            chatId.encodeToByteArray(),
        )
        return deserialize(plain)
    }

    // атомарная перезапись журнала (K1): запись во временный файл, fsync,
    // затем rename в том же каталоге. Обрыв в любом месте оставляет
    // исходный файл целым — худший исход — осиротевший .tmp, который
    // убирается при следующем открытии
    private fun rewriteAtomic(file: File, lines: List<String>) {
        val tmp = File(chatDir, file.name + ".tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                val text = lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else ""
                out.write(text.encodeToByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // не трогаем исходный файл: история важнее неудачного удаления
                throw java.io.IOException("journal: атомарная замена не удалась для ${file.name}")
            }
        } finally {
            // после успешного rename tmp уже не существует; после сбоя — чистим
            tmp.delete()
        }
    }

    // chatId попадает в имя файла — валидация на границе доверия
    private fun chatFile(chatId: String): File {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(chatId)) { "некорректный chatId" }
        return File(chatDir, "chat-$chatId.log")
    }

    // формат записи: [длина from 2Б][from][timestamp 8Б][content]
    private fun serialize(record: MessageRecord): ByteArray {
        val from = record.from.encodeToByteArray()
        require(from.size <= 65535) { "слишком длинный отправитель" }
        val head = ByteArray(10)
        head[0] = (from.size shr 8).toByte()
        head[1] = from.size.toByte()
        var ts = record.timestamp
        for (i in 9 downTo 2) {
            head[i] = (ts and 0xFF).toByte()
            ts = ts shr 8
        }
        return head + from + record.content
    }

    private fun deserialize(body: ByteArray): MessageRecord {
        val fromLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val from = body.copyOfRange(10, 10 + fromLen).decodeToString()
        var ts = 0L
        for (i in 2 until 10) ts = (ts shl 8) or (body[i].toLong() and 0xFF)
        return MessageRecord(from, ts, body.copyOfRange(10 + fromLen, body.size))
    }
}
