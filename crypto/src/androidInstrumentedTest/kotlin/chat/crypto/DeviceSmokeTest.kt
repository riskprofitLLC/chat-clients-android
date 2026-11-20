package chat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Smoke на реальном Android: хранилище ключей и полный round-trip на устройстве
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    @Test
    fun vaultWrapUnwrapKeepsSecret() {
        val secret = CryptoPrimitives.generateKeyPair().first
        val restored = KeyVaultAndroid.unwrap(KeyVaultAndroid.wrap(secret))
        assertArrayEquals(secret, restored)
    }

    @Test
    fun sealedBoxRoundTripOnDevice() {
        val alice = E2EFactory.generate()
        val bob = E2EFactory.generate()
        val message = "привет с устройства".encodeToByteArray()
        assertArrayEquals(message, bob.open(alice.seal(bob.publicKey(), message)))
    }

    @Test
    fun vaultedIdentitySurvivesReload() {
        // имитация перезапуска приложения: секрет ушёл в хранилище и вернулся из него
        val (secret, pub) = CryptoPrimitives.generateKeyPair()
        val other = E2EFactory.generate()
        val reloaded = E2EFactory.fromKeys(KeyVaultAndroid.unwrap(KeyVaultAndroid.wrap(secret)), pub)
        val message = "после перезапуска".encodeToByteArray()
        assertArrayEquals(message, reloaded.open(other.seal(pub, message)))
        assertEquals(91, pub.size)
    }
}
