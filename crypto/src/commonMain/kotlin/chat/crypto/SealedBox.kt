package chat.crypto

// Stateless sealed box; формат: [SPKI ephemeral 91Б][IV 12Б][шифротекст||тег]
object SealedBox {
    const val PUBLIC_KEY_SIZE = 91 // SPKI для P-256
    private const val IV_SIZE = 12
    private const val KEY_SIZE = 32
    private val INFO = "chat/crypto v1".encodeToByteArray()

    fun seal(recipientSpki: ByteArray, plaintext: ByteArray): ByteArray {
        require(recipientSpki.size == PUBLIC_KEY_SIZE) { "некорректный публичный ключ" }
        val (secretE, pubE) = CryptoPrimitives.generateKeyPair()
        val shared = CryptoPrimitives.ecdh(secretE, recipientSpki)
        // соль связывает вывод с обеими ключами; info фиксирует версию схемы
        val salt = CryptoPrimitives.sha256(pubE + recipientSpki)
        val key = Hkdf.derive(shared, salt, INFO, KEY_SIZE)
        val iv = CryptoPrimitives.randomBytes(IV_SIZE)
        val ct = CryptoPrimitives.aesGcmEncrypt(key, iv, plaintext, recipientSpki)
        return pubE + iv + ct
    }

    fun open(secretPkcs8: ByteArray, ownSpki: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > PUBLIC_KEY_SIZE + IV_SIZE) { "короткое сообщение" }
        val pubE = sealed.copyOfRange(0, PUBLIC_KEY_SIZE)
        val iv = sealed.copyOfRange(PUBLIC_KEY_SIZE, PUBLIC_KEY_SIZE + IV_SIZE)
        val ct = sealed.copyOfRange(PUBLIC_KEY_SIZE + IV_SIZE, sealed.size)
        val shared = CryptoPrimitives.ecdh(secretPkcs8, pubE)
        val salt = CryptoPrimitives.sha256(pubE + ownSpki)
        val key = Hkdf.derive(shared, salt, INFO, KEY_SIZE)
        return CryptoPrimitives.aesGcmDecrypt(key, iv, ct, ownSpki)
    }
}
