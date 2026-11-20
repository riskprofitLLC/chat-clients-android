package chat.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// actual на системной JCA: один и тот же код для JVM (тесты) и Android (Conscrypt)
actual object CryptoPrimitives {
    private val random = SecureRandom()

    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    actual fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    actual fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val pub = pair.public.encoded // SPKI
        check(pub.size == SealedBox.PUBLIC_KEY_SIZE) { "неожиданный размер SPKI: ${pub.size}" }
        return Pair(pair.private.encoded, pub) // PKCS8 и SPKI
    }

    actual fun ecdh(secretPkcs8: ByteArray, publicSpki: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("EC")
        val priv = factory.generatePrivate(PKCS8EncodedKeySpec(secretPkcs8))
        val pub = factory.generatePublic(X509EncodedKeySpec(publicSpki))
        return KeyAgreement.getInstance("ECDH")
            .apply { init(priv); doPhase(pub, true) }
            .generateSecret() // x-координата общей точки, 32 байта
    }

    actual fun aesGcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = gcm(Cipher.ENCRYPT_MODE, key, iv, plaintext, aad)

    actual fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = gcm(Cipher.DECRYPT_MODE, key, iv, ciphertext, aad)

    private fun gcm(
        mode: Int,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
        aad: ByteArray,
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        updateAAD(aad)
        doFinal(data)
    }
}
