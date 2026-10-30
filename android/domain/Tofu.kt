package chat.client.domain

// Чистая логика TOFU: сравнение известных ключей устройства собеседника
// с текущим составом. Вердикт решает, молча запомнить, продолжать или
// блокировать отправку до подтверждения пользователем.
object Tofu {

    sealed interface Verdict {
        // первый контакт: запомнить молча, баннера нет
        data object FirstSeen : Verdict

        // состав совпадает с запомненным
        data object Ok : Verdict

        // ключ известного устройства изменился — подмена или переустановка
        data class Changed(val devices: Set<String>) : Verdict

        // у известного пира появилось новое устройство
        data class NewDevice(val devices: Set<String>) : Verdict
    }

    fun digestOf(pub: ByteArray): String =
        chat.crypto.CryptoPrimitives.sha256(pub).joinToString("") { "%02x".format(it) }

    // A2: нужна ли повторная сверка кода после обновления состава устройств.
    // Сброс — если ключ известного устройства изменился ИЛИ появилось новое
    // устройство (прежний код покрывал только старый состав).
    fun requiresReverify(previous: Map<String, String>, current: List<Pair<String, ByteArray>>): Boolean {
        if (previous.isEmpty()) return false // первое знакомство — сверять нечего
        for ((deviceId, pub) in current) {
            val digest = digestOf(pub)
            val was = previous[deviceId] ?: return true // новое устройство
            if (was != digest) return true // ключ изменился
        }
        return false
    }

    // known: deviceId → digest hex; current — живой состав с сервера
    fun verdict(known: Map<String, String>, current: List<Pair<String, ByteArray>>): Verdict {
        if (known.isEmpty()) return Verdict.FirstSeen
        val changed = HashSet<String>()
        val added = HashSet<String>()
        for ((deviceId, pub) in current) {
            val digest = digestOf(pub)
            val stored = known[deviceId]
            when {
                stored == null -> added += deviceId
                stored != digest -> changed += deviceId
            }
        }
        return when {
            changed.isNotEmpty() -> Verdict.Changed(changed)
            added.isNotEmpty() -> Verdict.NewDevice(added)
            else -> Verdict.Ok
        }
    }
}
