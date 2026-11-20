package chat.client.data

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract

// Запись в системную адресную книгу телефона (нужен WRITE_CONTACTS).
// Все составные операции — одним applyBatch (атомарно, с back-reference),
// результат — sealed ContactsResult, пустых catch нет.
object PhoneBook {

    private fun <T> run(block: () -> T): ContactsResult<T> {
        return try {
            ContactsResult.Ok(block())
        } catch (_: SecurityException) {
            ContactsResult.NoPermission
        } catch (e: Exception) {
            ContactsResult.ProviderError(e.message ?: e.javaClass.simpleName)
        }
    }

    // создаёт контакт в системной книге; данные — одним batch после создания raw
    fun addContact(resolver: ContentResolver, name: String, phone: String, email: String): ContactsResult<Long> {
        return run {
            val raw = ContentValues()
            raw.putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
            raw.putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
            val uri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw)
                ?: throw IllegalStateException("no raw uri")
            val rawId = uri.lastPathSegment?.toLongOrNull()
                ?: throw IllegalStateException("no raw id")
            val ops = ArrayList<ContentProviderOperation>()
            if (name.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.trim())
                    .build()
            }
            if (phone.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.trim())
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            }
            if (email.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim().lowercase())
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                    .build()
            }
            if (ops.isNotEmpty()) resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            rawId
        }
    }

    // удаляет контакт из системной книги (raw contact целиком)
    fun deleteContact(resolver: ContentResolver, rawContactId: Long): ContactsResult<Boolean> {
        return run {
            resolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                ContactsContract.RawContacts._ID + "=?",
                arrayOf(rawContactId.toString()),
            ) > 0
        }
    }

    // обновляет отображаемое имя контакта
    fun updateName(resolver: ContentResolver, rawContactId: Long, name: String): ContactsResult<Boolean> {
        return run {
            val values = ContentValues()
            values.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            val where = (ContactsContract.Data.RAW_CONTACT_ID + "=? AND " +
                ContactsContract.Data.MIMETYPE + "=?")
            val args = arrayOf(
                rawContactId.toString(),
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            )
            if (resolver.update(ContactsContract.Data.CONTENT_URI, values, where, args) > 0) {
                true
            } else {
                val v = ContentValues()
                v.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                v.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                v.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                resolver.insert(ContactsContract.Data.CONTENT_URI, v) != null
            }
        }
    }

    // добавляет номер телефона к контакту
    fun addPhoneToContact(resolver: ContentResolver, rawContactId: Long, phone: String): ContactsResult<Boolean> {
        if (phone.isBlank()) return ContactsResult.Ok(false)
        return run {
            val v = ContentValues()
            v.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            v.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            v.put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.trim())
            v.put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            resolver.insert(ContactsContract.Data.CONTENT_URI, v) != null
        }
    }

    // заменяет все номера телефона контакта новым списком — одной транзакцией
    fun setPhones(resolver: ContentResolver, rawContactId: Long, phones: List<String>): ContactsResult<Boolean> {
        return run {
            val ops = ArrayList<ContentProviderOperation>()
            ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    ContactsContract.Data.RAW_CONTACT_ID + "=? AND " +
                        ContactsContract.Data.MIMETYPE + "=?",
                    arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                )
                .build()
            for (p in phones.filter { it.isNotBlank() }.map { it.trim() }) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, p)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        }
    }

    // заменяет все e-mail контакта новым списком — одной транзакцией
    fun setEmails(resolver: ContentResolver, rawContactId: Long, emails: List<String>): ContactsResult<Boolean> {
        return run {
            val ops = ArrayList<ContentProviderOperation>()
            ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    ContactsContract.Data.RAW_CONTACT_ID + "=? AND " +
                        ContactsContract.Data.MIMETYPE + "=?",
                    arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE),
                )
                .build()
            for (e in emails.filter { it.isNotBlank() }.map { it.trim().lowercase() }) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, e)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                    .build()
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        }
    }

    // добавляет e-mail к существующему контакту книги (rawContactId)
    fun addEmailToContact(resolver: ContentResolver, rawContactId: Long, email: String): ContactsResult<Boolean> {
        if (email.isBlank()) return ContactsResult.Ok(false)
        return run {
            val v = ContentValues()
            v.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            v.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            v.put(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim().lowercase())
            v.put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
            resolver.insert(ContactsContract.Data.CONTENT_URI, v) != null
        }
    }

    // все телефоны контакта
    fun phonesOf(resolver: ContentResolver, rawContactId: Long): List<String> {
        val out = mutableListOf<String>()
        run {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID + "=?",
                arrayOf(rawContactId.toString()), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out += it } }
        }
        return out
    }

    // все e-mail контакта
    fun emailsOf(resolver: ContentResolver, rawContactId: Long): List<String> {
        val out = mutableListOf<String>()
        run {
            resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                ContactsContract.CommonDataKinds.Email.RAW_CONTACT_ID + "=?",
                arrayOf(rawContactId.toString()), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out += it } }
        }
        return out
    }

    // неразрушающее связывание дублей через AggregationExceptions
    // (TYPE_KEEP_TOGETHER): обе записи остаются в книге, но показываются как один
    fun linkTogether(resolver: ContentResolver, rawId1: Long, rawId2: Long): ContactsResult<Boolean> {
        return run {
            val v = ContentValues()
            v.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rawId1)
            v.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawId2)
            v.put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
            resolver.insert(ContactsContract.AggregationExceptions.CONTENT_URI, v) != null
        }
    }

    // объединяет весь кластер дублей (список rawId) в один через связывание
    fun mergeCluster(resolver: ContentResolver, clusterIds: List<Long>): ContactsResult<Long?> {
        if (clusterIds.size < 2) return ContactsResult.Ok(clusterIds.firstOrNull())
        val keep = clusterIds.first()
        for (dup in clusterIds.drop(1)) {
            when (val r = linkTogether(resolver, keep, dup)) {
                is ContactsResult.Ok -> {}
                else -> return ContactsResult.ProviderError("link failed: $r")
            }
        }
        return ContactsResult.Ok(keep)
    }
}
