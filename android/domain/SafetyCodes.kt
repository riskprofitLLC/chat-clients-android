package chat.client.domain

import chat.crypto.CryptoPrimitives
import chat.crypto.SafetyCode

// Коды сверки для каждого устройства собеседника: (device_id, код).
// Логика без UI — тестируется инструментed-тестом и переиспользуется экраном.
fun safetyCodes(myPub: ByteArray, devices: List<Pair<String, ByteArray>>): List<Pair<String, String>> =
    devices.map { (deviceId, pub) -> deviceId to SafetyCode.of(myPub, pub) }

// формат для показа: «123456» → «123 456»
fun String.groupedCode(): String =
    if (length == 6) "${substring(0, 3)} ${substring(3)}" else this

// Отпечаток одного ключа (A10): короткий цифровой дайджест pub-ключа,
// одинаковый на обоих экранах привязки — сверяется глазами. 12 цифр
// (~40 бит): 6 цифр подбираются перебором за секунды (A10)
object KeyFingerprint {

    // 12 десятичных цифр из sha256(pub), группами по 3
    fun of(pubB64: String): String {
        val pub = try {
            java.util.Base64.getDecoder().decode(pubB64)
        } catch (_: IllegalArgumentException) {
            return ""
        }
        if (pub.isEmpty()) return ""
        val hash = CryptoPrimitives.sha256(pub)
        var n = 0L
        for (i in 0 until 5) n = (n shl 8) or (hash[i].toLong() and 0xFF)
        val digits = "%012d".format(n % 1_000_000_000_000L)
        return digits.chunked(3).joinToString(" ")
    }
}
