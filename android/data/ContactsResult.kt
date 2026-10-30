package chat.client.data

// Типизированный результат операций с адресной книгой: SecurityException
// (нет разрешения) не смешивается со сбоем провайдера — UI покажет причину.
sealed class ContactsResult<out T> {
    data class Ok<T>(val value: T) : ContactsResult<T>()
    data object NoPermission : ContactsResult<Nothing>()
    data object NotFound : ContactsResult<Nothing>()
    data class ProviderError(val message: String) : ContactsResult<Nothing>()
}
