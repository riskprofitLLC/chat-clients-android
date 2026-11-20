package chat.client.domain

import chat.crypto.MessageRecord
import chat.crypto.MessageStore

// Применение правки к локальному журналу. Чистая логика без Android —
// тестируется на JVM с JournalMessageStore.
//
// Цель ищется по ref (байты контента идентичны у обеих сторон), фолбэк —
// по ts для старых записей. Правку применяем ТОЛЬКО к сообщению автора
// кадра (A4): собеседник не может подменить чужой (в т.ч. наш) текст.
// Новый контент обязан быть TYPE_EDITED (для группы — конвертом той же
// группы), иначе событие отклоняется. ts цели сохраняется — позиция
// в ленте и таймеры самоудаления не страдают. Реакции на старый ref
// умирают вместе с ним — снимаем, как при удалении.
object MessageEdit {

    fun apply(
        store: MessageStore,
        chatId: String,
        expectedAuthor: String?,
        targetRef: String?,
        targetTs: Long?,
        newContent: ByteArray,
        window: Int = 2000, // окно поиска цели (C1)
    ): Boolean {
        if (!validNewContent(chatId, newContent)) return false
        val history = store.history(chatId, window).sortedBy { it.timestamp }
        val target = history.firstOrNull {
            targetRef != null && MessageRef.matches(targetRef, it.content)
        } ?: history.firstOrNull { targetTs != null && it.timestamp == targetTs }
            ?: return false
        // цель обязана принадлежать автору правки (A4)
        if (expectedAuthor != null && target.from != expectedAuthor) return false
        // повтор уже применённой правки (фолбэк по ts находит новую
        // запись): ничего не добавляем, но снимаем дубль от обрыва
        // между шагами прошлого применения
        if (target.content.contentEquals(newContent)) {
            var kept = false
            store.deleteRecords(chatId) { record ->
                record.timestamp == target.timestamp &&
                    record.content.contentEquals(newContent) &&
                    if (kept) true else {
                        kept = true
                        false
                    }
            }
            return true
        }
        // сначала добавляем новую запись, ЗАТЕМ убираем старую и реакции
        // одним пакетом (A3/K3): обрыв между шагами оставляет дубль
        // нового содержимого, а не теряет правку; дубль снимается
        // при следующем применении тем же пакетом
        store.append(chatId, MessageRecord(target.from, target.timestamp, newContent))
        var keptNew = false
        store.deleteRecords(chatId) { record ->
            val isOldTarget = record.timestamp == target.timestamp &&
                record.content.contentEquals(target.content)
            val isStaleReaction = run {
                val parsed = MessageCodec.parse(record.content, allowLegacy = false)
                parsed is MessageCodec.Parsed.ReactionOnly &&
                    (parsed.reaction.targetRef == targetRef ||
                        (parsed.reaction.targetRef == null && parsed.reaction.targetTs == target.timestamp))
            }
            // точный дубль нового содержимого (обрыв между шагами):
            // первый экземпляр живёт, повторные — уходят
            val isDuplicateNew =
                !isOldTarget && record.timestamp == target.timestamp &&
                    record.content.contentEquals(newContent) &&
                    if (keptNew) true else {
                        keptNew = true
                        false
                    }
            isOldTarget || isStaleReaction || isDuplicateNew
        }
        return true
    }

    // новый контент правки: TYPE_EDITED, для группы — конверт ТОЙ ЖЕ
    // группы с TYPE_EDITED внутри (A4); размер уже ограничен decodeV2
    private fun validNewContent(chatId: String, content: ByteArray): Boolean {
        val envelope = Envelope.decode(content) ?: return false
        return if (envelope.type == Envelope.TYPE_EDITED) {
            content.size <= EditFormat.MAX_EDIT_CONTENT
        } else if (envelope.type == Envelope.TYPE_GROUP) {
            if (content.size > EditFormat.MAX_EDIT_CONTENT * 2) return false
            val decoded = GroupFormat.decodeV2(envelope.payload) ?: return false
            decoded.group.id == chatId &&
                Envelope.decode(decoded.inner)?.type == Envelope.TYPE_EDITED
        } else {
            false
        }
    }
}
