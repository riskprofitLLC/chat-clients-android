package chat.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VectorsTest {
    // --- канонические векторы примитивов ---

    @Test
    fun sha256Abc() {
        assertContentEquals(
            hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            CryptoPrimitives.sha256("abc".encodeToByteArray()),
        )
    }

    @Test
    fun hmacRfc4231Case1() {
        assertContentEquals(
            hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            CryptoPrimitives.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()),
        )
    }

    @Test
    fun hkdfRfc5869Case1() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertContentEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun aesGcmMcGrewViegaCase1() {
        val sealed = CryptoPrimitives.aesGcmEncrypt(ByteArray(16), ByteArray(12), ByteArray(0), ByteArray(0))
        assertContentEquals(hex("58e2fccefa7e3061367f1d57a4e7455a"), sealed)
        assertContentEquals(
            ByteArray(0),
            CryptoPrimitives.aesGcmDecrypt(ByteArray(16), ByteArray(12), sealed, ByteArray(0)),
        )
    }

    @Test
    fun ecdhRfc5903Vector() {
        val factory = KeyFactory.getInstance("EC")
        val template = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        // параметры кривой берём из системной генерации, чтобы не собирать ECParameterSpec руками
        val params = factory.getKeySpec(template.public, ECPublicKeySpec::class.java).params

        fun priv(scalar: String): ByteArray =
            factory.generatePrivate(ECPrivateKeySpec(bigInteger(scalar), params)).encoded

        fun pub(x: String, y: String): ByteArray =
            factory.generatePublic(ECPublicKeySpec(ECPoint(bigInteger(x), bigInteger(y)), params)).encoded

        val i = "C88F01F5 10D9AC3F 70A292DA A2316DE5 44E9AAB8 AFE84049 C62A9C57 862D1433"
        val iX = "DAD0B653 94221CF9 B051E1FE CA5787D0 98DFE637 FC90B9EF 945D0C37 72581180"
        val iY = "5271A046 1CDB8252 D61F1C45 6FA3E59A B1F45B33 ACCF5F58 389E0577 B8990BB3"
        val r = "C6EF9C5D 78AE012A 011164AC B397CE20 88685D8F 06BF9BE0 B283AB46 476BEE53"
        val rX = "D12DFB52 89C8D4F8 1208B702 70398C34 2296970A 0BCCB74C 736FC755 4494BF63"
        val rY = "56FBF3CA 366CC23E 8157854C 13C58D6A AC23F046 ADA30F83 53E74F33 039872AB"
        val shared = "D6840F6B 42F6EDAF D13116E0 E1256520 2FEF8E9E CE7DCE03 812464D0 4B9442DE"

        val expected = fixed32(shared)
        assertContentEquals(expected, CryptoPrimitives.ecdh(priv(i), pub(rX, rY)))
        assertContentEquals(expected, CryptoPrimitives.ecdh(priv(r), pub(iX, iY)))
    }

    // --- композиция схемы ---

    @Test
    fun publicKeyIsSpki91() {
        assertEquals(91, E2EFactory.generate().publicKey().size)
    }

    @Test
    fun sealedRoundTrip() {
        val alice = E2EFactory.generate()
        val bob = E2EFactory.generate()
        val message = "привет, мир".encodeToByteArray()
        assertContentEquals(message, bob.open(alice.seal(bob.publicKey(), message)))
    }

    @Test
    fun emptyMessageRoundTrip() {
        val a = E2EFactory.generate()
        val b = E2EFactory.generate()
        assertContentEquals(ByteArray(0), b.open(a.seal(b.publicKey(), ByteArray(0))))
    }

    @Test
    fun largeMessageRoundTrip() {
        val a = E2EFactory.generate()
        val b = E2EFactory.generate()
        // ponytail: буфер в памяти; для больших медиа — стриминговая нарезка, если понадобится
        val message = ByteArray(1_000_000) { (it % 251).toByte() }
        assertContentEquals(message, b.open(a.seal(b.publicKey(), message)))
    }

    @Test
    fun selfSendRoundTrip() {
        val a = E2EFactory.generate()
        val message = "self".encodeToByteArray()
        assertContentEquals(message, a.open(a.seal(a.publicKey(), message)))
    }

    @Test
    fun fromKeysKeepsIdentity() {
        val (secret, pub) = CryptoPrimitives.generateKeyPair()
        val restored = E2EFactory.fromKeys(secret, pub)
        val other = E2EFactory.generate()
        val message = "restore".encodeToByteArray()
        assertContentEquals(message, restored.open(other.seal(restored.publicKey(), message)))
    }

    @Test
    fun tamperedTagFails() {
        val a = E2EFactory.generate()
        val b = E2EFactory.generate()
        val sealed = a.seal(b.publicKey(), "тест".encodeToByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertFailsWith<Exception> { b.open(sealed) }
    }

    @Test
    fun tamperedHeaderFails() {
        val a = E2EFactory.generate()
        val b = E2EFactory.generate()
        val sealed = a.seal(b.publicKey(), "тест".encodeToByteArray())
        sealed[10] = (sealed[10].toInt() xor 1).toByte()
        assertFailsWith<Exception> { b.open(sealed) }
    }

    @Test
    fun wrongRecipientFails() {
        val a = E2EFactory.generate()
        val b = E2EFactory.generate()
        val c = E2EFactory.generate()
        val sealed = a.seal(b.publicKey(), "secret".encodeToByteArray())
        assertFailsWith<Exception> { c.open(sealed) }
    }

    // --- сверка ключей ---

    @Test
    fun safetyCodeIsSymmetricAndSixDigits() {
        val a = E2EFactory.generate().publicKey()
        val b = E2EFactory.generate().publicKey()
        val code = SafetyCode.of(a, b)
        assertEquals(code, SafetyCode.of(b, a))
        assertEquals(6, code.length)
        assertTrue(code.all { it in '0'..'9' })
    }

    // --- утилиты ---

    private fun hex(s: String): ByteArray =
        s.filter { !it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun bigInteger(s: String): BigInteger = BigInteger(s.filter { !it.isWhitespace() }, 16)

    private fun fixed32(s: String): ByteArray {
        val raw = bigInteger(s).toByteArray()
        return when {
            raw.size == 33 -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> raw
        }
    }
}
