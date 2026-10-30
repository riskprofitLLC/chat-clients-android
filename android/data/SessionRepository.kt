package chat.client.data

import android.content.Context
import android.util.Log
import chat.client.domain.Validators
import chat.client.net.RelayException
import chat.crypto.CryptoPrimitives
import chat.crypto.SafetyCode

// Аутентификация и жизненный цикл сессии: регистрация, вход, линковка,
// перенос, бэкап. Идентичность создаётся В ПАМЯТИ и коммитится на диск
// ТОЛЬКО после успеха сервера. Сторе — единый StoreGate из AppContainer (A12).
class SessionRepository(private val container: AppContainer) {

    private val context: Context = container.app
    private val api get() = container.api

    // незакоммиченная идентичность ожидающей линковки: живёт в памяти,
    // при смерти процесса просто пропадает (nonce на сервере истечёт)
    private class PendingIdentity(val secret: ByteArray, val pub: ByteArray, val pubB64: String)

    @Volatile private var pendingIdentity: PendingIdentity? = null
    @Volatile private var pendingLink: LinkState? = null

    // ─── регистрация ───

    // регистрация: ключи генерируются в памяти; на диск — после успеха сервера.
    // overwriteIdentity: аккаунт на устройстве меняется — ПОЛНАЯ очистка
    // (история, outbox, группы, known_keys) ТОЛЬКО ПОСЛЕ успешного ответа
    // сервера (A6): неверный OTP больше не стирает данные
    fun registerWithCode(email: String, code: String, password: String, overwriteIdentity: Boolean): SessionInfo {
        if (!overwriteIdentity && Session.hasIdentity(context)) throw Session.IdentityExistsException()
        val (secret, pub) = CryptoPrimitives.generateKeyPair()
        val pubB64 = android.util.Base64.encodeToString(pub, android.util.Base64.NO_WRAP)
        val (token, login) = api.registerWithCode(
            email, code, password, SessionStore.deviceId(context), pubB64,
        )
        if (overwriteIdentity) Session.wipeEverything(context)
        commitIdentity(secret, pub, pubB64)
        SessionStore.saveToken(context, token)
        SessionStore.saveLogin(context, login)
        SessionStore.saveEmail(context, email.trim().lowercase())
        // новый аккаунт: очередь старого не имеет смысла (A2)
        container.outbox.onLogout()
        if (overwriteIdentity) container.reopenStore()
        return readySession(token, login, secret, pub)
    }

    // вход с этого же устройства: идентичность уже есть, сервер ротирует токен.
    // Проверяем, что сервер знает ИМЕННО наш ключ для этого device_id —
    // вход под чужим аккаунтом иначе даст нечитаемые сообщения (A11)
    fun loginByIdentifier(identifier: String, password: String): SessionInfo {
        check(Session.hasIdentity(context)) { "нет ключей — войдите через привязку устройства" }
        val (token, login) = api.loginByIdentifier(identifier, password, SessionStore.deviceId(context))
        val localPub = SessionStore.pubB64(context)
            ?: throw chat.client.net.KeysUnavailableException()
        val devices = try {
            api.peerKeys(login)
        } catch (_: Exception) {
            emptyList()
        }
        val mine = devices.firstOrNull { it.first == SessionStore.deviceId(context) }
        if (mine != null) {
            val serverPubB64 = android.util.Base64.encodeToString(mine.second, android.util.Base64.NO_WRAP)
            if (serverPubB64 != localPub) {
                throw chat.client.net.KeysMismatchException()
            }
        }
        SessionStore.saveToken(context, token)
        SessionStore.saveLogin(context, login)
        if (identifier.contains("@")) SessionStore.saveEmail(context, identifier.trim().lowercase())
        // повторный вход тем же аккаунтом: СВОИ записи в очереди живут
        // дальше и уйдут при появлении сети; стираем только чужие (A2)
        container.outbox.onLogout(keepLogin = login)
        return loadReady() ?: throw chat.client.net.KeysUnavailableException()
    }

    // ─── линковка нового устройства ───

    // Запуск: только на устройстве БЕЗ идентичности. Ключи в памяти,
    // переоткрытие экрана переиспользует живой LinkState. Отпечаток
    // нового ключа показывается на ОБОИХ устройствах (A10)
    fun prepareLink(): LinkState {
        pendingLink?.let { return it }
        check(!Session.hasIdentity(context)) { "на устройстве уже есть ключи — линковка не нужна" }
        if (pendingIdentity == null) {
            val (secret, pub) = CryptoPrimitives.generateKeyPair()
            pendingIdentity = PendingIdentity(
                secret, pub,
                android.util.Base64.encodeToString(pub, android.util.Base64.NO_WRAP),
            )
        }
        val identity = pendingIdentity!!
        // 2.5: сервер отдаёт и одноразовый секрет опроса — токен по коду
        // больше не может забрать первый встречный, кто узнал nonce
        val init = api.linkInit(SessionStore.deviceId(context), identity.pubB64)
        val code = "${init.nonce}|${identity.pubB64}|${SessionStore.deviceId(context)}"
        return LinkState(code, init.nonce, chat.client.domain.KeyFingerprint.of(identity.pubB64), container.clock.now(), init.pollSecret)
            .also { pendingLink = it }
    }

    // отказ от линковки: незакоммиченная идентичность отбрасывается
    fun cancelLink() {
        pendingIdentity = null
        pendingLink = null
    }

    // одноразовый опрос; null — ещё подтверждается; истёкший nonce — исключение.
    // 2.5: опрос несёт одноразовый секрет с link/init
    fun pollLinkOnce(link: LinkState): SessionInfo? {
        val (token, login) = api.linkPoll(link.nonce, link.pollSecret.ifEmpty { null }) ?: return null
        val identity = pendingIdentity
            ?: throw RelayException(410, "код привязки истёк, начните заново")
        commitIdentity(identity.secret, identity.pub, identity.pubB64)
        SessionStore.saveToken(context, token)
        SessionStore.saveLogin(context, login)
        pendingIdentity = null
        pendingLink = null
        return readySession(token, login, identity.secret, identity.pub)
    }

    // ─── подтверждение на старом устройстве ───

    class LinkConfirmation(
        val nonce: String,
        val pubB64: String,
        val newDeviceId: String?,
        val fingerprint: String, // код сверки с ОСНОВНЫМ устройством
        val newKeyFingerprint: String, // отпечаток НОВОГО ключа (A10)
    )

    // разбор кода, код сверки и отпечаток нового ключа для диалога
    fun parseLinkRequest(code: String): LinkConfirmation? {
        val (nonce, pubB64, deviceId) = Session.parseLinkCode(code) ?: return null
        val pub = try {
            android.util.Base64.decode(pubB64, android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return LinkConfirmation(
            nonce, pubB64, deviceId,
            SafetyCode.of(currentPub(), pub),
            chat.client.domain.KeyFingerprint.of(pubB64),
        )
    }

    private fun currentPub(): ByteArray {
        val pubB64 = SessionStore.pubB64(context) ?: throw RelayException(409, "нет ключей")
        return android.util.Base64.decode(pubB64, android.util.Base64.NO_WRAP)
    }

    // подтверждение после явного диалога: привязка + уведомление остальных
    fun confirmLink(confirmation: LinkConfirmation): Session.ConfirmResult {
        val result = api.linkConfirm(currentSessionToken() ?: "", confirmation.nonce, confirmation.pubB64)
        if (result is chat.client.net.LinkConfirmResult.Ok) {
            currentSession()?.let { session ->
                container.messageRepo.notifyNewDevice(session, confirmation.newDeviceId ?: "?", confirmation.fingerprint)
            }
        }
        return Session.ConfirmResult(result is chat.client.net.LinkConfirmResult.Ok, confirmation.newDeviceId, result)
    }

    private fun currentSessionToken(): String? = SessionStore.token(context)

    private fun currentSession(): SessionInfo? = (container.sessionState.value as? SessionState.Ready)?.info

    // ─── выход ───

    // выход: очередь привязана к логину и НЕ стирается при обычном
    // выходе — записи дожидаются повторного входа и сети (A2).
    // Полное стирание — только при wipeData («выйти и удалить данные»)
    fun logout(wipeData: Boolean) {
        val login = SessionStore.login(context)
        if (wipeData) Session.wipeEverything(context) else Session.logout(context)
        pendingIdentity = null
        pendingLink = null
        container.outbox.onLogout(keepLogin = if (wipeData) null else login)
        if (wipeData) container.reopenStore()
    }

    // ─── бэкап с ключами (решение владельца (b)) ───

    fun exportIdentityOrNull(): Triple<ByteArray, String, String>? {
        return try {
            val secret = SessionStore.loadSecret(context) ?: return null
            val pub = SessionStore.pubB64(context) ?: return null
            Triple(secret, pub, SessionStore.deviceId(context))
        } catch (e: Exception) {
            Log.w("SessionRepo", "identity недоступен для бэкапа", e)
            null
        }
    }

    // восстановление из бэкапа: стереть сломанное, записать ключи из бэкапа.
    // deviceId берём из бэкапа: сервер уже знает pub этого устройства (A11).
    // После wipe переоткрываем журнал: синглтон держал открытый каталог (A6)
    fun restoreIdentity(secret: ByteArray, pubB64: String, deviceId: String) {
        SessionStore.wipeIdentityAndData(context)
        SessionStore.saveDeviceId(context, deviceId)
        SessionStore.saveIdentity(context, secret, pubB64)
        container.outbox.onLogout() // идентичность сменилась — очередь невалидна
        container.reopenStore()
    }

    // ─── вспомогательные ───

    private fun commitIdentity(secret: ByteArray, pub: ByteArray, pubB64: String) {
        SessionStore.saveIdentity(context, secret, pubB64)
    }

    private fun readySession(token: String, login: String, secret: ByteArray, pub: ByteArray): SessionInfo {
        return SessionInfo(
            login = login,
            token = token,
            deviceId = SessionStore.deviceId(context),
            e2e = chat.crypto.E2EFactory.fromKeys(secret, pub),
            store = container.storeGate,
            api = api,
        )
    }

    private fun loadReady(): SessionInfo? =
        (Session.load(context, api, container.storeGate) as? SessionState.Ready)?.info

    // загрузка при старте: обновляет container.sessionState
    fun restoreSession(): SessionState {
        val state = Session.load(context, api, container.storeGate)
        container.setSessionState(state)
        return state
    }

    companion object {
        // нормализация email перед хэшированием/отправкой
        fun normalizeEmail(raw: String): String =
            raw.trim().lowercase().replace(" ", "")
    }
}
