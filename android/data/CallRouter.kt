package chat.client.data

// Приёмник живых call-событий (Фаза GA): репозиторий гоняет сюда свежие
// события звонка из дрейна. Фаза GB повесит сюда CallManager (ринг и
// медиа); до него подписчика нет — события оседают только записью
// «пропущенный звонок» в журнале.
interface CallRouter {
    fun onCallEvent(from: String, event: chat.client.domain.CallFormat.Event)
}
