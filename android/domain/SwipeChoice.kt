package chat.client.domain

// AK6 (аналог SwipeGestureSettingsView у Telegram): действие свайпа
// строки чата выбирается для каждой стороны отдельно — закрепить,
// без звука, архив, прочитано или отключить. Дефолты — прежнее
// поведение (вправо закреп, влево мьют), ни у кого жесты не меняются.
enum class SwipeChoice(val key: String) {
    PIN("pin"),
    MUTE("mute"),
    ARCHIVE("archive"),
    READ("read"),
    NONE("none");

    companion object {
        // разбор настройки; мусор — прежний дефолт стороны
        fun parse(raw: String?, fallback: SwipeChoice): SwipeChoice =
            entries.firstOrNull { it.key == raw } ?: fallback
    }
}
