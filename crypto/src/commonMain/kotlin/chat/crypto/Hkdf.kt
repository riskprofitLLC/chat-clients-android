package chat.crypto

// HKDF по RFC 5869 (extract-then-expand) поверх HMAC-SHA256
object Hkdf {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        CryptoPrimitives.hmacSha256(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "некорректная длина OKM: $length" }
        var okm = ByteArray(0)
        var t = ByteArray(0)
        var counter = 1
        while (okm.size < length) {
            t = CryptoPrimitives.hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            okm += t
            counter++
        }
        return okm.copyOf(length)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}
