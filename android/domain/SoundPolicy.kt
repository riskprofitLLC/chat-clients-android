package chat.client.domain

// Политика звуков в приложении (по образцу Telegram: звук включается
// настройкой, не играет в беззвучном режиме и дросселируется по времени)
object SoundPolicy {

    // троттлинг, мс: входящее — как в Telegram (500), исходящее — 100
    const val IN_THROTTLE_MS = 500L
    const val OUT_THROTTLE_MS = 100L

    // входящее: настройка, мьют чата, беззвучный режим, пауза между звуками;
    // Фаза X1: беззвучное сообщение не играет звук у получателя вовсе
    fun shouldPlayIncoming(
        enabled: Boolean,
        chatMuted: Boolean,
        ringerSilent: Boolean,
        msSinceLastPlay: Long,
        silent: Boolean = false, // Фаза X1: отправитель просил без звука
    ): Boolean =
        enabled && !chatMuted && !ringerSilent && !silent && msSinceLastPlay >= IN_THROTTLE_MS

    // исходящее: настройка, беззвучный режим, пауза между звуками
    fun shouldPlayOutgoing(
        enabled: Boolean,
        ringerSilent: Boolean,
        msSinceLastPlay: Long,
    ): Boolean = enabled && !ringerSilent && msSinceLastPlay >= OUT_THROTTLE_MS
}
