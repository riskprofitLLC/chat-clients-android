package chat.client.domain

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// PBKDF2-HMAC-SHA256 (RFC 8018) на переиспользуемом Mac.
// Провайдер «PBKDF2WithHmacSHA256» появился только в API 26 (A7), а minSdk 24:
// экспорт/импорт бэкапа и PIN падали на Android 7.x. Вывод байт-в-байт
// совпадает с системным провайдером — старые бэкапы и PIN-хэши остаются
// валидными, векторы — в тестах (RFC 7914).
object Pbkdf2 {

    fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        require(iterations in 1..10_000_000) { "некорректное число итераций" }
        require(keyLen in 1..1024) { "некорректная длина ключа" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLen = mac.macLength
        val out = ByteArray(keyLen)
        var offset = 0
        var block = 1
        val counter = ByteArray(4)
        while (offset < keyLen) {
            counter[0] = (block ushr 24).toByte()
            counter[1] = (block ushr 16).toByte()
            counter[2] = (block ushr 8).toByte()
            counter[3] = block.toByte()
            var u = mac.doFinal(salt + counter)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val take = minOf(t.size, keyLen - offset)
            t.copyInto(out, offset, 0, take)
            offset += take
            block++
        }
        return out
    }
}
