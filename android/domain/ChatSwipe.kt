package chat.client.domain

// AI4 (аналог свайпа между чатами у Telegram): сосед текущего чата
// по порядку списка — свайп от края экрана листает диалоги, как
// в Telegram. Чистая логика индексов — тестируется на JVM.
object ChatSwipe {

    // сосед по списку: forward — следующий (ниже в списке), иначе —
    // предыдущий; null — текущего нет в списке или соседа не существует
    fun neighbor(ids: List<String>, current: String, forward: Boolean): String? {
        val index = ids.indexOf(current)
        if (index < 0) return null
        val target = if (forward) index + 1 else index - 1
        if (target !in ids.indices) return null
        return ids[target]
    }

    // список для свайпа: скрытые чаты не раскрываются свайпом (AH4 —
    // факт их существования не виден), архив листается внутри себя?
    // нет: свайп ходит по ОСНОВНОМУ списку, без архивных
    fun swipeableIds(ids: List<String>, archived: Set<String>, hidden: Set<String>): List<String> =
        ids.filter { it !in archived && it !in hidden }

    // в какую сторону листать: свайп от ЛЕВОГО края вправо — назад,
    // от ПРАВОГО края влево — вперёд (жест Telegram)
    fun forward(fromLeftEdge: Boolean, dragDelta: Float): Boolean? {
        if (abs(dragDelta) < 1f) return null
        return if (fromLeftEdge) {
            if (dragDelta > 0) false else null // вправо — предыдущий
        } else {
            if (dragDelta < 0) true else null // влево — следующий
        }
    }

    private fun abs(v: Float) = if (v < 0) -v else v
}
