package chat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Хранение секрета устройства: AES-ключ живёт в AndroidKeyStore и не покидает его.
// Формат обёртки: [IV 12Б][шифротекст||тег]. Библиотека не решает, куда писать
// обёртку — файловое хранилище выбирает приложение.
object KeyVaultAndroid {
    private const val ALIAS = "chat-crypto-vault"
    private const val IV_SIZE = 12

    fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        return cipher.iv + cipher.doFinal(secret)
    }

    fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_SIZE) { "короткая обёртка" }
        val iv = wrapped.copyOfRange(0, IV_SIZE)
        val ct = wrapped.copyOfRange(IV_SIZE, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    // ключ создаётся при первом обращении и переиспользуется
    private fun aesKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
