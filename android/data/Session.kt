package chat.client.data

import android.content.Context
import android.util.Log
import chat.client.domain.CallGate
import chat.client.domain.DedupIndex
import chat.client.domain.MessageCodec
import chat.client.net.RelayApi
import chat.crypto.AndroidMessageStore
import chat.crypto.CryptoPrimitives
import chat.crypto.E2E
import chat.crypto.E2EFactory
import chat.crypto.MessageRecord
import chat.crypto.MessageStore
import java.io.File

private const val TAG = "Session"

// Результат загрузки сессии: три состояния вместо «null при любой беде».
// KeysUnavailable — Keystore недоступен (смена блокировки, восстановление
// системы): ключи на диске есть, но развернуть нельзя — пользователю
// нужен экран выбора: восстановить из бэкапа / привязать заново / стереть.
sealed class SessionState {
    data object NoSession : SessionState()
    class KeysUnavailable(val detail: String? = null) : SessionState()
    class Ready(val info: SessionInfo) : SessionState()
}

// Ключи собеседника изменились/появились — отправка блокируется до
// явного подтверждения (TOFU). peer — логин (для групп: участник).
// partialDelivered — участники группы, УЖЕ получившие конверт до
// блокировки: прогресс сохраняется, после accept копий не будет (A2)
class KeyChangedException(
    val peer: String,
    val devices: Set<String>,
    val isNewDevice: Boolean,
    val partialDelivered: Set<String> = emptySet(),
) : Exception("keys_changed:$peer")

// Активная сессия: логин, токен, идентичность, сторе, API.
// Транспорт (дрейн/отправка) — в MessageRepository.
class SessionInfo(
    val login: String,
    val token: String,
    val deviceId: String,
    val e2e: E2E,
    val api: RelayApi,
    val store: StoreGate,
) {
    // дедупликация входящих внутри одной сессии (список блобов)
    val dedup = DedupIndex()

    // гейт звонков (Фаза GA): идемпотентность терминальных событий гонки устройств
    val calls = CallGate()

    // seal одного payload для набора устройств (fan-out)
    fun sealFor(devices: List<Pair<String, ByteArray>>, payload: ByteArray): List<Pair<String, ByteArray>> =
        devices.map { (id, pub) -> id to e2e.seal(pub, payload) }

    fun dedupKey(from: String, blob: ByteArray): String =
        CryptoPrimitives.sha256(from.encodeToByteArray() + blob).joinToString("") { "%02x".format(it) }
}

// Состояние линковки на новом устройстве: код для старого + nonce для опроса.
// Идентичность держится В ПАМЯТИ (SessionRepository.pendingIdentity) и
// попадает на диск только после успеха — выход с экрана ничего не портит.
class LinkState(
    val code: String,
    val nonce: String,
    val fingerprint: String,
    val createdAt: Long,
    // 2.5: одноразовый секрет опроса — житьёт на НОВОМ устройстве,
    // в QR не попадает (QR сканирует старое устройство)
    val pollSecret: String = "",
)

object Session {

    // адрес сервера из BuildConfig: debug — локальный релей, release — прод
    fun server(): String = chat.client.BuildConfig.DEFAULT_SERVER

    fun email(context: Context): String? = SessionStore.email(context)

    // есть ли на устройстве идентичность (секретный ключ)
    fun hasIdentity(context: Context): Boolean = SessionStore.hasIdentityFile(context)

    // выход: чистим токен и логин, ключи и история остаются для повторного входа
    fun logout(context: Context) = SessionStore.clearSession(context)

    // «Выйти и удалить данные»: полная очистка устройства
    fun wipeEverything(context: Context) = SessionStore.wipeAll(context)

    // Загрузка сессии с диска. Порядок (A6): сначала ключи — их наличие
    // не зависит от Keystore; затем vault: сломанный Keystore при живых
    // ключах — KeysUnavailable, а не «нет сессии»; токен читается последним
    fun load(context: Context, api: RelayApi, store: StoreGate): SessionState {
        if (!SessionStore.hasIdentityFile(context)) return SessionState.NoSession
        if (!SecretBox.vaultHealthy(context)) {
            Log.e(TAG, "Keystore-хранилище префов недоступно")
            return SessionState.KeysUnavailable("vault")
        }
        val token = SessionStore.token(context)
        val login = SessionStore.login(context)
        if (token == null || login == null) return SessionState.NoSession // не входили/вышли
        val pubB64 = SessionStore.pubB64(context) ?: return SessionState.KeysUnavailable("identity_missing")
        val secret = try {
            SessionStore.loadSecret(context)
        } catch (e: Exception) {
            Log.e(TAG, "не удалось развернуть ключ идентичности", e)
            null
        } ?: return SessionState.KeysUnavailable("identity")
        val pub = android.util.Base64.decode(pubB64, android.util.Base64.NO_WRAP)
        return SessionState.Ready(
            SessionInfo(
                login = login,
                token = token,
                deviceId = SessionStore.deviceId(context),
                e2e = E2EFactory.fromKeys(secret, pub),
                store = store,
                api = api,
            ),
        )
    }

    class IdentityExistsException : Exception("на устройстве уже есть ключи другого аккаунта")

    // Подтверждение кода линковки на старом устройстве:
    // формат «nonce|pubB64[|deviceId]»
    class ConfirmResult(
        val confirmed: Boolean,
        val newDeviceId: String?,
        // A8: причина отказа — истёк код / ключ не совпал / конфликт
        val reason: chat.client.net.LinkConfirmResult? = null,
    )

    // парсинг кода линковки «nonce|pubB64[|deviceId]»: части валидируются
    // ДО показа в диалоге (A10): nonce — hex, pub — base64 ключа, id — регэксп
    private val linkNonceRe = Regex("^[0-9a-f]{16,64}$")
    private val linkDeviceRe = Regex("^[a-z0-9_-]{1,64}$")

    fun parseLinkCode(code: String): Triple<String, String, String?>? {
        val parts = code.trim().split("|")
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        if (!linkNonceRe.matches(parts[0])) return null
        val pub = try {
            android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // SPKI P-256 — 91 байт; меньше 32 — точно не ключ
        if (pub.size < 32) return null
        val deviceId = parts.getOrNull(2)?.ifEmpty { null }
        if (deviceId != null && !linkDeviceRe.matches(deviceId)) return null
        return Triple(parts[0], parts[1], deviceId)
    }

    // helper: проверка, что контент — системное уведомление о новом устройстве
    fun deviceInfoOf(content: ByteArray): chat.client.domain.DeviceFormat.Decoded? {
        val envelope = chat.client.domain.Envelope.decode(content) ?: return null
        if (envelope.type != chat.client.domain.Envelope.TYPE_DEVICE) return null
        return chat.client.domain.DeviceFormat.decode(envelope.payload)
    }

    // парсинг входящего контента: v1-декодеры выключены
    fun parseIncoming(content: ByteArray): MessageCodec.Parsed =
        MessageCodec.parse(content, allowLegacy = false)

    // парсинг локальной записи: v1 разрешён только записям до миграции
    fun parseRecord(record: MessageRecord, legacySince: Long): MessageCodec.Parsed =
        MessageCodec.parse(record.content, allowLegacy = record.timestamp < legacySince)
}

// Скачивание транзитного медиа: расшифровка контент-ключом + локальная копия.
// null — не удалось; история сообщения от этого не зависит.
fun downloadAndStoreMedia(
    context: Context,
    api: RelayApi,
    token: String,
    mediaId: String,
    contentKey: ByteArray,
): ByteArray? = try {
    val blob = api.downloadMedia(token, mediaId)
    val plain = CryptoPrimitives.aesGcmDecrypt(
        contentKey,
        blob.copyOfRange(0, 12),
        blob.copyOfRange(12, blob.size),
        ByteArray(0),
    )
    chat.client.media.Media.save(context, mediaId, plain)
    plain
} catch (_: Exception) {
    null
}
