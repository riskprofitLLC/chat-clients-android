package chat.client.domain

// A4: фильтр событий звонка. Событие (кроме нового OFFER) принимается ТОЛЬКО
// если его callId совпадает с текущим и from == собеседник звонка. Иначе любой
// собеседник мог оборвать чужой идущий звонок (EV_BYE/CANCEL/BUSY) или вбросить
// ICE-кандидаты.
object CallEventGate {

    enum class Decision { ACCEPT, IGNORE }

    fun decide(
        ev: String,
        eventCallId: String,
        from: String,
        currentCallId: String?,
        remotePeer: String?,
    ): Decision {
        if (ev == CallFormat.EV_OFFER) return Decision.ACCEPT // новый входящий
        val id = currentCallId ?: return Decision.IGNORE     // звонка нет — нечего
        val peer = remotePeer ?: return Decision.IGNORE
        if (eventCallId != id) return Decision.IGNORE        // чужой звонок
        if (from != peer) return Decision.IGNORE             // не наш собеседник
        return Decision.ACCEPT
    }

    // A4: валидация ICE-кандидата — формат, длина и запрет loopback/link-local.
    // Кандидат моста (isBridge) допускается отдельно: он адресует localhost.
    fun validCandidate(candidate: String, isBridge: Boolean): Boolean {
        if (candidate.isBlank() || candidate.length > 512) return false
        val c = candidate.lowercase()
        // адрес не должен быть loopback/link-local, если это не наш мост
        val suspicious = c.contains("127.0.0.1") || c.contains("::1") ||
            c.contains("169.254.") || c.contains("fe80:") || c.contains("[::1]")
        if (suspicious && !isBridge) return false
        return true
    }
}
