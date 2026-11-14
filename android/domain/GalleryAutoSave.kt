package chat.client.domain

// Автосохранение входящих медиа в галерею (аналог «Save to Gallery»
// у Telegram): настройка на чат, включённая — новые фото и видео
// пишутся в системную галерею при докачке из транзита. Чистый фильтр:
// какие дескрипторы подходят — аватары и не-медиа не сейвим.
object GalleryAutoSave {

    // фото/видео сообщения: аватары (свои и групп) не трогаем,
    // голосовые и документы — тоже
    fun savable(info: MediaInfo): Boolean {
        if (info.avatarOwner != null || info.avatarRemove) return false
        if (info.id.isEmpty()) return false
        val mime = info.mime ?: return info.type == "media"
        return mime.startsWith("image/") || mime.startsWith("video/")
    }
}
