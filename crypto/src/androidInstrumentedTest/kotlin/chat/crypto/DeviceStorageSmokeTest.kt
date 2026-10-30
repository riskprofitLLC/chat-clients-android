package chat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Smoke на устройстве: локальная история переживает «перезапуск», ключ данных
// живёт только в обёртке AndroidKeyStore
@RunWith(AndroidJUnit4::class)
class DeviceStorageSmokeTest {

    private fun storeDir(name: String): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)

    @Test
    fun historySurvivesReopenOnDevice() {
        val dir = storeDir("smoke-history")
        dir.deleteRecursively()
        AndroidMessageStore.open(dir).append("alice", MessageRecord("alice", 77L, "на устройстве".encodeToByteArray()))

        // «перезапуск» приложения: новый экземпляр, тот же диск
        val history = AndroidMessageStore.open(dir).history("alice")
        assertEquals(1, history.size)
        assertEquals("alice", history[0].from)
        assertEquals(77L, history[0].timestamp)
        assertArrayEquals("на устройстве".encodeToByteArray(), history[0].content)
    }

    @Test
    fun deliveredMessageLandsInLocalHistory() {
        val dir = storeDir("smoke-e2e")
        dir.deleteRecursively()
        val alice = E2EFactory.generate()
        val bob = E2EFactory.generate()
        val message = "расшифровано и сохранено".encodeToByteArray()

        // полный путь: transport → open → локальное хранилище → перезапуск → чтение
        val sealed = alice.seal(bob.publicKey(), message)
        val store = AndroidMessageStore.open(dir)
        store.append("with-alice", MessageRecord("alice", 1234L, bob.open(sealed)))

        val reopened = AndroidMessageStore.open(dir)
        val history = reopened.history("with-alice")
        assertEquals(1, history.size)
        assertArrayEquals(message, history[0].content)
        assertEquals("alice", history[0].from)
    }

    @Test
    fun vaultedDataKeyStaysWrappedOnDisk() {
        val dir = storeDir("smoke-vault")
        dir.deleteRecursively()
        AndroidMessageStore.open(dir).append("chat", MessageRecord("u", 1L, "x".encodeToByteArray()))
        val wrappedKey = File(dir, "vault.key").readBytes()
        // обёртка = IV 12Б + ключ 32Б + тег 16Б; сырого ключа данных на диске нет
        assertEquals(60, wrappedKey.size)
        // ключ живой: хранилище переоткрывается и читает историю
        assertEquals(1, AndroidMessageStore.open(dir).history("chat").size)
    }
}
