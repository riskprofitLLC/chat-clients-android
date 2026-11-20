package chat.client.data

// Часы для тестируемости: реализация подставляется в тестах
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
