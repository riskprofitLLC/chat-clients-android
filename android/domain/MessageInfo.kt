package chat.client.domain

// AH5 (аналог Message info у Telegram): модель «когда отправлено,
// доставлено, прочитано» для одного сообщения. Данные уже локальны:
// deliveredAt/readAt чата (1:1) и groupDeliveredAt по участникам.
// Чистая логика — UI только форматирует.
object MessageInfo {

    class Model(
        val sentAt: Long,
        val deliveredAt: Long?, // null — ещё не доставлено
        val readAt: Long?,      // null — ещё не прочитано (только 1:1)
        // группа: участники, забравшие сообщение ПОСЛЕ его отправки,
        // отсортированы по времени доставки
        val groupDelivered: List<Pair<String, Long>>,
        val groupNotDelivered: List<String>,
    )

    // время доставки имеет смысл только ПОСЛЕ отправки: подтверждение
    // могло прийти по более ранним сообщениям этого чата
    fun build(
        sentAt: Long,
        deliveredAt: Long,
        readAt: Long,
        groupDeliveredAt: Map<String, Long>,
        members: List<String>,
    ): Model {
        val delivered = deliveredAt.takeIf { it >= sentAt && it > 0 }
        val read = readAt.takeIf { it >= sentAt && it > 0 }
        val deliveredMembers = groupDeliveredAt
            .filterValues { it >= sentAt && it > 0 }
            .toList()
            .sortedBy { it.second }
        val deliveredSet = deliveredMembers.map { it.first }.toSet()
        return Model(
            sentAt = sentAt,
            deliveredAt = delivered,
            readAt = read,
            groupDelivered = deliveredMembers,
            groupNotDelivered = members.filter { it !in deliveredSet },
        )
    }
}
