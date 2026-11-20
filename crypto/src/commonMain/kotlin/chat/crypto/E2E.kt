package chat.crypto

// Интерфейс E2E-блока: одна идентичность = одно устройство.
// ponytail: нет форвард-секреции; на Signal/MLS менять реализацию, не интерфейс
interface E2E {
    fun publicKey(): ByteArray

    fun seal(recipientPub: ByteArray, plaintext: ByteArray): ByteArray

    fun open(ciphertext: ByteArray): ByteArray
}

object E2EFactory {
    fun generate(): E2E {
        val (secret, pub) = CryptoPrimitives.generateKeyPair()
        return CryptoIdentity(secret, pub)
    }

    fun fromKeys(secret: ByteArray, pub: ByteArray): E2E = CryptoIdentity(secret, pub)
}

// Идентичность устройства: пара ключей P-256 в блобах PKCS8/SPKI
internal class CryptoIdentity(
    private val secret: ByteArray,
    private val pub: ByteArray,
) : E2E {
    override fun publicKey(): ByteArray = pub

    override fun seal(recipientPub: ByteArray, plaintext: ByteArray): ByteArray =
        SealedBox.seal(recipientPub, plaintext)

    override fun open(ciphertext: ByteArray): ByteArray =
        SealedBox.open(secret, pub, ciphertext)
}
