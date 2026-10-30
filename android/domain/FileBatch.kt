package chat.client.domain

// Мультивыбор файлов (Фаза AB8): системный пикер GetMultipleContents не
// умеет maxItems — потолок пачки держим сами, как у фото (PhotoBatch).
object FileBatch {
    const val MAX = 10

    // пачка не больше потолка; лишние — отсекаются, итог честно в снеке
    fun <T> limit(items: List<T>): List<T> = items.take(MAX)
}
