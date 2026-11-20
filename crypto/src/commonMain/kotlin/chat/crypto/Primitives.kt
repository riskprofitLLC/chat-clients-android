package chat.crypto

// Платформенные криптопримитивы; реализуются на системных провайдерах:
// JVM/Android — JCA (Conscrypt/BoringSSL или JDK), iOS — CryptoKit.
expect object CryptoPrimitives {
    fun randomBytes(size: Int): ByteArray

    fun sha256(data: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    // возвращает (секретный PKCS8, публичный SPKI)
    fun generateKeyPair(): Pair<ByteArray, ByteArray>

    fun ecdh(secretPkcs8: ByteArray, publicSpki: ByteArray): ByteArray

    fun aesGcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray
}
