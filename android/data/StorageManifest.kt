package chat.client.data

import android.content.Context
import java.io.File

// 3.6: единый список всех хранилищ с данными аккаунта — единственный
// источник правды для wipeAll/wipeIdentityAndData. Новый каталог или
// prefs-файл обязан попасть сюда, иначе полное стирание оставит хвосты
// (прежде забывались обои чатов, кэш камеры и livegeo)
object StorageManifest {

    // каталоги в filesDir с пользовательскими данными
    val filesDirs = listOf(
        "identity", // секретный ключ (Keystore-обёртка)
        "store", // журнал истории
        "media", // расшифрованные медиа
        "vault", // обёртка ключа префов
        "outbox", // очередь отправки
        "drainwal", // WAL дрейна
        "quarantine", // карантин приглашений
        "stickers", // стикеры
        "gifs", // избранные GIF
        "wallpapers", // обои чатов (3.6: прежде не стирались)
    )

    // SharedPreferences с данными аккаунта; app_prefs чистится отдельно
    // (resetAccountData сохраняет тему), session — clearSession
    val prefs = listOf(
        "groups",
        "chat_prefs",
        "destruct",
        "unread",
        "known_keys",
        "blocked_senders",
        "avatars",
        "bios",
        "livegeo", // 3.6: активная трансляция геопозиции
        "contact_book", // связи контактов книги с e-mail/логинами
        "edit_history", // предыдущие версии сообщений
        "group_events", // журнал событий групп
        "profile_gallery", // галерея профиля
        "stories", // подписи и миниатюры сторис
        "translations", // переводы сообщений
        "widget_prefs", // превью виджета
        "call_log", // журнал звонков экрана «Звонки»
    )

    // удалить все filesDir-каталоги манифеста
    fun wipeFilesDirs(context: Context) {
        for (name in filesDirs) {
            File(context.filesDir, name).deleteRecursively()
        }
    }

    // кэш целиком: каталоги camera/shared и осиротевшие voice-*/vnote-*
    // записи после сбоя — всё содержимое одноразовое
    fun wipeCache(context: Context) {
        context.cacheDir.deleteRecursively()
    }

    // удалить все prefs-файлы манифеста
    fun wipePrefs(context: Context) {
        for (name in prefs) {
            context.deleteSharedPreferences(name)
        }
    }
}
