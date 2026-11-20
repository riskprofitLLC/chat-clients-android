package chat.client.data

import android.content.Context
import android.provider.ContactsContract

// Поиск raw-контакта в системной книге по e-mail (для правок из чата).
object PhoneBookSearch {

    fun byEmail(context: Context, email: String): Long? {
        if (!email.contains("@")) return null
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.RAW_CONTACT_ID),
                ContactsContract.CommonDataKinds.Email.ADDRESS + "=?",
                arrayOf(email.trim().lowercase()),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}
