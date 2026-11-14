package chat.client.domain

// Группа: локальное определение (id, имя, участники, ревизия состава).
// rev растёт на каждое изменение имени/состава (A3); value-равенство —
// data class даёт стабильный hashCode для кэша сводок (B9)
data class GroupChat(
    val id: String,
    val name: String,
    val members: List<String>,
    val rev: Long = 0,
    // Фаза AB10: режим канала — логин владельца, только он пишет;
    // null — обычная группа (все пишут)
    val broadcast: String? = null,
    // Фаза AC3 (аналог About/Description): описание группы, до 255;
    // пустая строка — описания нет
    val desc: String = "",
    // Фаза AC4 (аналог slow mode): пауза между сообщениями участников,
    // секунд; 0 — выключен
    val slowMode: Int = 0,
    // Фаза AD6 (аналог «Restrict saving content»): запрет пересылки и
    // сохранения содержимого; как все групповые флаги — клиентский
    val noForward: Boolean = false,
    // AI3 (аналог admin rights у Telegram): администраторы — могут
    // удалять чужие сообщения (гейт приёма/отправки); назначение — как
    // остальные настройки: любой участник, ревизионно
    val admins: List<String> = emptyList(),
    // Фаза AJ (аналог Allowed Reactions: none): реакции в группе выключены
    val noReactions: Boolean = false,
)

// 1.4: единая сборка группового конверта из определения группы. Прежде
// ~15 копий 9-аргументного вызова GroupFormat.encode рассыпались при
// добавлении полей: sendGroupRename терял noForward и снимал запрет
// пересылки у всех получателей
fun GroupChat.wrap(payload: ByteArray): ByteArray =
    GroupFormat.encode(id, name, members, payload, rev, broadcast, desc, slowMode, noForward, admins, noReactions)

// Чистая логика членства в группах — тестируется на JVM без Context.
object GroupRules {

    // Фаза AB10 (аналог canSendMessages у каналов Telegram): писать
    // может владелец канала; в обычной группе — все
    fun canWrite(group: GroupChat?, myLogin: String): Boolean =
        group == null || group.broadcast == null || group.broadcast == myLogin

    // - отправитель обязан входить в состав из самого сообщения;
    // - для известной группы отправитель обязан входить в ЕЁ известный состав.
    fun validateMembership(sender: String, info: GroupInfo, known: GroupChat?): Boolean {
        if (sender !in info.members) return false
        if (known != null && sender !in known.members) return false
        return true
    }

    // Проверка входящего группового сообщения (анти-подмена состава):
    // неизвестную группу создаём, только если в составе есть и отправитель,
    // и я сам (иначе чужая группа навязывает себя в список чатов).
    fun validateIncoming(
        myLogin: String,
        sender: String,
        info: GroupInfo,
        known: GroupChat?,
    ): Boolean {
        if (!validateMembership(sender, info, known)) return false
        if (known == null && !(sender in info.members && myLogin in info.members)) return false
        return true
    }

    // Классификация изменения состава против известного (A9):
    // добавление требует подтверждения, удаление применяется молча.
    class MembershipChange(
        val sender: String,
        val added: List<String>, // кого добавили
        val removed: List<String>, // кого убрали
    ) {
        val hasAdditions: Boolean get() = added.isNotEmpty()
        val hasRemovals: Boolean get() = removed.isNotEmpty()
        val isSame: Boolean get() = added.isEmpty() && removed.isEmpty()
    }

    fun membershipChange(sender: String, known: GroupChat, newMembers: List<String>): MembershipChange {
        val added = newMembers.filter { it !in known.members }
        val removed = known.members.filter { it !in newMembers }
        return MembershipChange(sender, added, removed)
    }

    // Фаза Z5 (аналог groups in common): группы, где есть и я, и собеседник —
    // раздел «Общие группы» в инфо-экране 1:1; порядок — как в справочнике
    fun commonGroups(myLogin: String, peer: String, groups: List<GroupChat>): List<GroupChat> =
        groups.filter { peer in it.members && myLogin in it.members }

    // ─── ревизии состава (A3) ───

    // Потолок скачка rev: конверт с rev далеко впереди известного
    // отклоняется. Иначе один участник с rev=MaxLong заблокирует все
    // будущие изменения навсегда (локальный rev+1 переполнится в минус)
    const val MAX_REV_JUMP = 1000L

    // из конверта rev принимаем, только если он новее, но не «из космоса»
    fun revAcceptable(infoRev: Long, knownRev: Long): Boolean =
        infoRev > knownRev && infoRev - knownRev <= MAX_REV_JUMP

    // локальная следующая ревизия — без переполнения
    fun nextRev(rev: Long): Long = if (rev >= Long.MAX_VALUE) rev else rev + 1

    // применять ли состав/имя из конверта: только у НОВЕЕ известного.
    // Конверты без v (rev=0) никогда не перетирают локальное состояние
    fun shouldApplyComposition(info: GroupInfo, known: GroupChat?): Boolean {
        if (known == null) return true
        return revAcceptable(info.rev, known.rev)
    }

    // отправитель — неподтверждённый новый участник (A3): его сообщения
    // уходят в карантин до accept, а не теряются как «вне состава»
    fun isUnconfirmedMember(sender: String, known: GroupChat?, pendingMembers: List<String>?): Boolean {
        if (known == null) return false
        if (sender in known.members) return false
        return pendingMembers != null && sender in pendingMembers
    }

    // ─── ручное изменение состава (Фаза M1) ───

    // Кандидат на добавление в существующую группу: members уже включает меня
    enum class AddMemberResult { OK, ALREADY_IN, LIMIT, INVALID }

    // Причина отказа правила: типизированная — UI маппит по коду, а не
    // разбором машинного текста в message (и не показом его пользователю)
    class GroupRuleException(val rule: String) : IllegalArgumentException(rule) {
        companion object {
            const val ALREADY_IN = "already_in"
            const val TOO_MANY_MEMBERS = "too_many_members"
            const val INVALID_LOGIN = "invalid_login"
        }
    }

    fun validateAdd(members: List<String>, myLogin: String, candidate: String): AddMemberResult = when {
        !Validators.validLogin(candidate) -> AddMemberResult.INVALID
        candidate == myLogin || candidate in members -> AddMemberResult.ALREADY_IN
        members.size + 1 > GroupFormat.MAX_MEMBERS -> AddMemberResult.LIMIT
        else -> AddMemberResult.OK
    }

    // Исключать можно любого участника, кроме себя (выход — отдельное действие)
    fun canRemoveMember(members: List<String>, myLogin: String, member: String): Boolean =
        member != myLogin && member in members

    // ─── переименование группы (Фаза N3) ───

    // Новое имя: непустое после trim, не длиннее 64 (лимит конверта)
    enum class RenameResult { OK, INVALID }

    fun validateName(name: String): RenameResult = when {
        name.isBlank() || name.trim().length > 64 -> RenameResult.INVALID
        else -> RenameResult.OK
    }

    // ─── описание группы (Фаза AC3, аналог About) ───

    // Готовое описание: trim + потолок 255; пустое — снятие описания
    const val MAX_DESC = 255

    fun sanitizeDesc(raw: String): String = raw.trim().take(MAX_DESC)

    // ─── администраторы (Фаза AI3, аналог admin rights у Telegram) ───

    // готовый список админов: только участники, без дублей, порядок стабильный
    fun sanitizeAdmins(admins: List<String>, members: List<String>): List<String> =
        admins.filter { it in members }.distinct()

    // право модерации: админ может удалять чужие сообщения
    fun isAdmin(admins: List<String>, login: String): Boolean = login in admins

    // AI3: админы для модерации ВХОДЯЩЕГО события — только из локального
    // состояния группы. Список "ad" из конверта недоверенный: иначе любой
    // участник, объявив себя админом, снёс бы чужое сообщение
    fun moderationAdmins(known: GroupChat?): Set<String> = known?.admins?.toSet() ?: emptySet()
    // локальная правка списка: toggle одного логина, без дублей
    fun toggleAdmin(admins: List<String>, members: List<String>, login: String): List<String> =
        if (login in admins) admins - login else sanitizeAdmins(admins + login, members)

    // Готовый текст об изменении состава больше не генерируется (B4):
    // в журнал пишется структура GroupChange, локализация — при показе
}
