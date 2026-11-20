package chat.client.data

import chat.client.domain.Validators
import chat.crypto.CryptoPrimitives
import chat.crypto.MessageRecord
import chat.crypto.MessageStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

// Локальный шифрованный бэкап всей истории: лекарство от «потерял оба устройства».
//
// Формат v3: [MAGIC "CHBK" 4Б][версия 1Б][JSON-заголовок len 4Б BE][заголовок]
//            [salt 16Б][iv 12Б][AES-GCM(тело)]. Тело — JSON-объект:
//            {"records":[{"chat","from","ts","content"}], "identity":{...}?},
//            где identity (опционально, решение владельца (b)) — секретный
//            ключ аккаунта: восстановление возможно при потере всех устройств.
// Заголовок: {"kdf":"pbkdf2-hmac-sha256","iter":600000} — параметры выносятся
// в файл, чтобы будущие версии могли менять стоимость KDF.
//
// Формат v2 (импорт сохранён): то же, но тело — чистый массив записей.
// Формат v1 (импорт сохранён): [salt 16Б][iv 12Б][ct||tag], 120k итераций.
object Backup {

    private val MAGIC = byteArrayOf(0x43, 0x48, 0x42, 0x4B) // "CHBK"
    private const val VERSION: Byte = 3
    private const val ITER_V3 = 600_000 // ≥600k для PBKDF2-HMAC-SHA256
    private const val ITER_V1 = 120_000 // старые файлы
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12

    // граница контента одной записи импорта: как у конвертов сообщений
    private const val MAX_RECORD_CONTENT = 12 * 1024 * 1024

    // границы параметров заголовка: защита от DoS странными файлами
    private const val ITER_MIN = 100_000
    private const val ITER_MAX = 5_000_000
    private const val HEADER_MAX = 4096

    // внятные причины неудачи для UI
    sealed class BackupException(message: String) : Exception(message) {
        class Corrupt : BackupException("corrupt")
        class BadPassword : BackupException("bad_password")
        class TooLarge : BackupException("too_large")
    }

    // ─── экспорт ───

    // возвращает число сохранённых записей; идентичность — с deviceId (A11):
    // восстановление поднимает тот же device_id, чей pub уже на сервере
    fun export(
        store: MessageStore,
        password: String,
        target: File,
        identitySecret: ByteArray? = null,
        identityPubB64: String? = null,
        identityDeviceId: String? = null,
    ): Int {
        target.parentFile?.mkdirs()
        val count = target.outputStream().use {
            export(store, password, it, identitySecret, identityPubB64, identityDeviceId)
        }
        return count
    }

    // запись в поток; возвращает число записей — без второго прохода (A24)
    fun export(
        store: MessageStore,
        password: String,
        out: OutputStream,
        identitySecret: ByteArray? = null,
        identityPubB64: String? = null,
        identityDeviceId: String? = null,
    ): Int {
        val records = collectRecords(store)
        val body = JSONObject().put("records", records)
        if (identitySecret != null && identityPubB64 != null) {
            // A6: поле device пишется только с реальным значением —
            // putOpt(NULL) ложил в файл строку "null", и импорт
            // принимал её как deviceId
            val identity = JSONObject()
                .put("secret", java.util.Base64.getEncoder().encodeToString(identitySecret))
                .put("pub", identityPubB64)
            if (identityDeviceId != null) identity.put("device", identityDeviceId)
            body.put("identity", identity)
        }
        val salt = CryptoPrimitives.randomBytes(SALT_SIZE)
        val iv = CryptoPrimitives.randomBytes(IV_SIZE)
        val header = JSONObject().put("kdf", "pbkdf2-hmac-sha256").put("iter", ITER_V3).toString()
        val headerBytes = header.encodeToByteArray()
        val key = deriveKey(password, salt, ITER_V3)
        val ct = CryptoPrimitives.aesGcmEncrypt(key, iv, body.toString().encodeToByteArray(), ByteArray(0))
        val headerLen = ByteArray(4)
        headerLen[0] = (headerBytes.size ushr 24).toByte()
        headerLen[1] = (headerBytes.size ushr 16).toByte()
        headerLen[2] = (headerBytes.size ushr 8).toByte()
        headerLen[3] = headerBytes.size.toByte()
        out.write(MAGIC)
        out.write(byteArrayOf(VERSION))
        out.write(headerLen)
        out.write(headerBytes)
        out.write(salt)
        out.write(iv)
        out.write(ct)
        out.flush()
        return records.length()
    }

    // счётчик записей без второй расшифровки истории
    fun count(store: MessageStore): Int {
        var count = 0
        for (chatId in store.chatIds()) count += store.history(chatId, Int.MAX_VALUE).size
        return count
    }

    // A6: экспорт собирает тело в памяти (формат v3) — на гигантских
    // историях это OOM, поэтому потолок задокументирован и проверяется
    // честным отказом; потоковый формат v4 — отдельным решением владельца
    private const val MAX_EXPORT_RECORDS = 500_000

    private fun collectRecords(store: MessageStore): JSONArray {
        val records = JSONArray()
        var count = 0
        for (chatId in store.chatIds()) {
            for (record in store.history(chatId, Int.MAX_VALUE)) {
                if (++count > MAX_EXPORT_RECORDS) {
                    throw BackupException.TooLarge()
                }
                records.put(
                    JSONObject()
                        .put("chat", chatId)
                        .put("from", record.from)
                        .put("ts", record.timestamp)
                        .put("content", java.util.Base64.getEncoder().encodeToString(record.content)),
                )
            }
        }
        return records
    }

    // ─── импорт: идемпотентный — повторный импорт не плодит дубли ───

    class Imported(val records: Int, val identity: Identity?, val skipped: Int = 0)

    class Identity(val secret: ByteArray, val pubB64: String, val deviceId: String? = null)

    fun import(store: MessageStore, file: File, password: String): Imported =
        file.inputStream().use { import(store, it, password) }

    // расшифровка без применения: для восстановления поверх сломанного
    // хранилища (сначала identity, потом записи в чистый сторе)
    class Decrypted(val records: JSONArray, val identity: Identity?)

    // потолок входного файла бэкапа (C1): гигантский файл не съедает память.
    // var для тестов (маленький потолок в OOM-кейсах), прод — 256 МБ
    @JvmField var maxInputBytes: Long = 256L * 1024 * 1024

    fun decrypt(input: InputStream, password: String): Decrypted {
        // лимит размера входа (C1): гигантский файл не съедает память.
        // 3.7: читаем ограниченно — readBytes() до проверки валил всё в
        // память (256 МБ+), а available() на SAF-стримах не работает.
        // readNBytes — API 33+, читаем руками с потолком
        val maxBytes = (maxInputBytes + 1).toInt()
        val out = java.io.ByteArrayOutputStream(1 shl 20)
        val buf = ByteArray(1 shl 16)
        input.use { stream ->
            var total = 0
            while (total < maxBytes) {
                val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
        }
        val blob = out.toByteArray()
        if (blob.size > maxInputBytes) throw BackupException.TooLarge()
        if (blob.size < SALT_SIZE + IV_SIZE + 16 + 2) throw BackupException.Corrupt()

        if (blob.size > MAGIC.size + 5 && blob.copyOfRange(0, 4).contentEquals(MAGIC)) {
            val version = blob[4]
            if (version == VERSION) {
                return decryptV3(blob, password)
            }
            if (version == 2.toByte()) {
                return Decrypted(decryptV2(blob, password), null)
            }
            throw BackupException.Corrupt()
        }
        // v1: 120k итераций, без заголовка
        val salt = blob.copyOfRange(0, SALT_SIZE)
        val iv = blob.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val ct = blob.copyOfRange(SALT_SIZE + IV_SIZE, blob.size)
        val plain = decrypt(deriveKey(password, salt, ITER_V1), iv, ct)
        val records = try {
            JSONArray(plain.decodeToString())
        } catch (_: Exception) {
            throw BackupException.Corrupt()
        }
        return Decrypted(records, null)
    }

    fun import(store: MessageStore, input: InputStream, password: String): Imported {
        val decrypted = decrypt(input, password)
        val stats = importBody(store, decrypted.records, allowIdentity = false)
        return Imported(stats.applied, decrypted.identity, stats.skipped)
    }

    // применяет расшифрованные записи (идемпотентно); возвращает число
    // применённых — пропущенные битые записи в счёт не входят
    fun apply(store: MessageStore, decrypted: Decrypted): Int =
        importBody(store, decrypted.records, allowIdentity = false).applied

    private fun decryptV3(blob: ByteArray, password: String): Decrypted {
        // заголовок с параметрами KDF: длина ограничена, границы проверены
        val headerLen = ((blob[5].toInt() and 0xFF) shl 24) or
            ((blob[6].toInt() and 0xFF) shl 16) or
            ((blob[7].toInt() and 0xFF) shl 8) or
            (blob[8].toInt() and 0xFF)
        if (headerLen <= 0 || headerLen > HEADER_MAX) throw BackupException.Corrupt()
        val bodyStart = 9 + headerLen
        if (bodyStart + SALT_SIZE + IV_SIZE + 16 > blob.size) throw BackupException.Corrupt()
        val header = try {
            JSONObject(blob.copyOfRange(9, bodyStart).decodeToString())
        } catch (_: Exception) {
            throw BackupException.Corrupt()
        }
        val iterations = header.optInt("iter", ITER_V3)
        if (iterations < ITER_MIN || iterations > ITER_MAX) throw BackupException.Corrupt()
        val salt = blob.copyOfRange(bodyStart, bodyStart + SALT_SIZE)
        val iv = blob.copyOfRange(bodyStart + SALT_SIZE, bodyStart + SALT_SIZE + IV_SIZE)
        val ct = blob.copyOfRange(bodyStart + SALT_SIZE + IV_SIZE, blob.size)
        val plain = decrypt(deriveKey(password, salt, iterations), iv, ct)
        val body = try {
            JSONObject(plain.decodeToString())
        } catch (_: Exception) {
            throw BackupException.Corrupt()
        }
        val records = body.optJSONArray("records") ?: throw BackupException.Corrupt()
        var identity: Identity? = null
        val idJson = body.optJSONObject("identity")
        if (idJson != null) {
            val secretB64 = idJson.optString("secret")
            val pub = idJson.optString("pub")
            val device = idJson.optString("device", "").ifEmpty { null }
            if (secretB64.isNotEmpty() && pub.isNotEmpty()) {
                try {
                    identity = Identity(java.util.Base64.getDecoder().decode(secretB64), pub, device)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        return Decrypted(records, identity)
    }

    private fun decryptV2(blob: ByteArray, password: String): JSONArray {
        val headerLen = ((blob[5].toInt() and 0xFF) shl 24) or
            ((blob[6].toInt() and 0xFF) shl 16) or
            ((blob[7].toInt() and 0xFF) shl 8) or
            (blob[8].toInt() and 0xFF)
        if (headerLen <= 0 || headerLen > HEADER_MAX) throw BackupException.Corrupt()
        val bodyStart = 9 + headerLen
        if (bodyStart + SALT_SIZE + IV_SIZE + 16 > blob.size) throw BackupException.Corrupt()
        val header = try {
            JSONObject(blob.copyOfRange(9, bodyStart).decodeToString())
        } catch (_: Exception) {
            throw BackupException.Corrupt()
        }
        val iterations = header.optInt("iter", ITER_V3).coerceIn(ITER_MIN, ITER_MAX)
        val salt = blob.copyOfRange(bodyStart, bodyStart + SALT_SIZE)
        val iv = blob.copyOfRange(bodyStart + SALT_SIZE, bodyStart + SALT_SIZE + IV_SIZE)
        val ct = blob.copyOfRange(bodyStart + SALT_SIZE + IV_SIZE, blob.size)
        val plain = decrypt(deriveKey(password, salt, iterations), iv, ct)
        return try {
            JSONArray(plain.decodeToString())
        } catch (_: Exception) {
            throw BackupException.Corrupt()
        }
    }

    // GCM-тег не сошёлся = неверный пароль или битый файл —
    // для пользователя это одна и та же диагностика
    private fun decrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray =
        try {
            CryptoPrimitives.aesGcmDecrypt(key, iv, ct, ByteArray(0))
        } catch (_: Exception) {
            throw BackupException.BadPassword()
        }

    // добавляет записи, пропуская уже существующие (chat, from, ts, content)
    class ImportStats(val applied: Int, val skipped: Int)

    private fun importBody(store: MessageStore, records: JSONArray, allowIdentity: Boolean): ImportStats {
        if (!allowIdentity && records.length() > 1_000_000) throw BackupException.Corrupt()
        // индекс существующих: chat → ts → записи (для точной сверки дублей)
        val byChat = HashMap<String, HashMap<Long, MutableList<MessageRecord>>>()
        for (chatId in store.chatIds()) {
            val known = byChat.getOrPut(chatId) { HashMap() }
            for (record in store.history(chatId, Int.MAX_VALUE)) {
                known.getOrPut(record.timestamp) { mutableListOf() }.add(record)
            }
        }
        var applied = 0
        var skipped = 0
        for (i in 0 until records.length()) {
            // A6: каждая запись валидируется отдельно — кривой chat/from/
            // размер не обрывает импорт посередине, запись просто пропускается
            val record = try {
                records.getJSONObject(i)
            } catch (_: Exception) {
                skipped++
                continue
            }
            val chat = record.optString("chat", "")
            val from = record.optString("from", "")
            val ts = record.optLong("ts", 0L)
            if (!Validators.validChatId(chat) || !Validators.validLogin(from) || ts <= 0L) {
                skipped++
                continue
            }
            val content = try {
                java.util.Base64.getDecoder().decode(record.getString("content"))
            } catch (_: IllegalArgumentException) {
                skipped++ // битая запись — пропускаем, остальные импортируем
                continue
            }
            // пустой контент — легальная (историческая) запись; отвергаем
            // только непомерное
            if (content.size > MAX_RECORD_CONTENT) {
                skipped++
                continue
            }
            val known = byChat.getOrPut(chat) { HashMap() }
            val bucket = known[ts]
            if (bucket != null && bucket.any { it.from == from && it.content.contentEquals(content) }) continue
            val newRecord = MessageRecord(from, ts, content)
            store.append(chat, newRecord)
            known.getOrPut(ts) { mutableListOf() }.add(newRecord)
            applied++
        }
        return ImportStats(applied, skipped)
    }

    // PBKDF2-HMAC-SHA256, 256 бит — своя реализация на Mac (A7):
    // провайдер PBKDF2WithHmacSHA256 есть только с API 26, а minSdk 24
    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray =
        chat.client.domain.Pbkdf2.derive(
            password.encodeToByteArray(), salt, iterations, 256 / 8,
        )
}
