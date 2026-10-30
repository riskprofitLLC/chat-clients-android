package chat.crypto

import java.io.File

// Фабрика хранилища на устройстве: ключ данных генерируется один раз,
// на диске лежит только в обёртке AndroidKeyStore. Потеря файлов = потеря
// истории — это выбранная модель (хранилище только на устройствах).
object AndroidMessageStore {
    fun open(appDir: File): MessageStore {
        appDir.mkdirs() // каталог хранилища может не существовать — создаём до записи ключа
        // 4.2: ключ данных — единый KeystoreDataKey (кэш развёрнутых ключей)
        val dataKey = KeystoreDataKey.dataKey(appDir)
        return JournalMessageStore(File(appDir, "chats"), dataKey)
    }
}
