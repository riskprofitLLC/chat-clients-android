package chat.client.domain

import chat.crypto.MessageRecord
import chat.crypto.MessageStore

// Цель закрепа в локальном журнале: по ref, фолбэк по ts для старых
// записей. Чистая логика — тестируется на JVM с JournalMessageStore.
object MessagePin {

    fun resolve(store: MessageStore, chatId: String, targetRef: String?, targetTs: Long?, window: Int = 2000): MessageRecord? {
        val history = store.history(chatId, window)
        history.firstOrNull { targetRef != null && MessageRef.matches(targetRef, it.content) }
            ?.let { return it }
        return history.firstOrNull { targetTs != null && it.timestamp == targetTs }
    }
}
