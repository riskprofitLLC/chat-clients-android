package chat.client.net

import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

// HTTP-клиент протокола сервера: OkHttp (единый стек с WS), org.json.
// Интерфейс — для подмены фейками в тестах.
interface RelayApi {
    // адрес сервера: QR привязки несёт его, чтобы новое устройство
    // из любой сети обращалось именно к этому релею
    fun baseUrl(): String

    // шаг 1: запросить код на email; типизированный результат вместо
    // проглоченных исключений (429/400 больше не «сервер недоступен»)
    fun requestCode(email: String): Result<Unit>

    fun registerWithCode(email: String, code: String, password: String, deviceId: String, pubB64: String): Pair<String, String>
    fun loginByIdentifier(identifier: String, password: String, deviceId: String): Pair<String, String>

    // все устройства пользователя: список (device_id, ключ); пусто — не зарегистрирован
    fun peerKeys(peer: String): List<Pair<String, ByteArray>>

    // fan-out: отдельный блоб для каждого устройства; kind: chat | transfer
    fun send(token: String, to: String, kind: String, blobs: List<Pair<String, ByteArray>>)

    // 2.2: batch-отправка — до 128 получателей одним запросом; лимит
    // сервера считается на сообщение, а не на блоб (группа из 50 прежде
    // упиралась в ~100 сообщений/час). Карта (получатель → HTTP-код)
    // для отклонённых; пустая — все доставлены
    fun sendBatch(token: String, kind: String, recipients: List<BatchRecipient>): Map<String, Int>

    // страница ack-дрейна (1.1): after — серверный id последней взятой
    // записи; выдаются только id > after. Сервер не удаляет до подтверждения
    // (ack=1), страница обрабатывается и подтверждается ДО запроса следующей —
    // иначе при >200 сообщениях клиент вечно получал бы первую страницу
    fun drainPage(token: String, after: Long): DrainPage

    // дрейн своей очереди: страницы крутятся до more=false (S4).
    // serverId — для подтверждения ackMessages после записи в WAL.
    // Устаревший путь для унаследованных вызовов: полный дрейн копит всё в
    // память; основной цикл живёт в MessageRepository по drainPage
    fun drain(token: String): List<RelayMessage> {
        val out = ArrayList<RelayMessage>()
        var after = 0L
        var pages = 0
        while (true) {
            val page = drainPage(token, after)
            out += page.messages
            if (!page.more) break
            val last = page.messages.lastOrNull()?.serverId ?: 0L
            // страховка: курсор не двигается или потолок итераций — выходим
            if (last <= after || ++pages > DRAIN_MAX_PAGES) break
            after = last
        }
        return out
    }

    // S4: подтверждение ack-дрейна — удалить свои записи по id после
    // обработки; сбой подтверждения не критичен (сервер отдаст снова, dedup снимет)
    fun ackMessages(token: String, ids: List<Long>)

    // Линковка: QR показывает НОВОЕ устройство (оно публикует свой ключ под
    // одноразовым кодом), ранее подключённое сканирует код и подтверждает.
    fun linkInit(deviceId: String, pubB64: String): LinkInitResult

    // A8: типизированный результат подтверждения линковки — прежде любая
    // ошибка схлопывалась в false и UI показывал безликий «неверный запрос»
    fun linkConfirm(token: String, nonce: String, pubB64: String): LinkConfirmResult

    // опрос кода новым устройством: null — pending; (token, user_id) — привязано.
    // pollSecret — одноразовый секрет из QR
    fun linkPoll(nonce: String, pollSecret: String): Pair<String, String>?

    // AH1 (аналог Terminate sessions у Telegram): ревокация устройства
    // аккаунта — ключ, токен и пуш отозванного устройства умирают разом
    fun revokeDevice(token: String, deviceId: String): Result<Unit>

    // AH9 (аналог смены номера у Telegram): смена email аккаунта по коду,
    // высланному обычным requestCode() на НОВУЮ почту
    fun changeEmail(token: String, newEmail: String, code: String): Result<Unit>

    // AH10 (аналог смены пароля у Telegram): старый пароль обязателен,
    // новый — не короче 6; сессии не отзываются
    fun changePassword(token: String, currentPassword: String, newPassword: String): Result<Unit>

    // AI1 (аналог Delete My Account у Telegram): полный снос аккаунта
    // на сервере с подтверждением паролем; после успеха локально — wipe
    fun deleteAccount(token: String, password: String): Result<Unit>

    // AJ: порог автоудаления аккаунта при простое (0/3/6/12 месяцев)
    fun setAccountInactivity(token: String, password: String, months: Int): Result<Unit>

    // GH2 (аналог Cloud Password): установка/снятие облачного пароля 2FA
    fun setCloudPassword(token: String, currentPassword: String, cloudPassword: String): Result<Unit>

    // GH3 (аналог Active Sessions): список своих устройств
    fun listDevices(token: String): Result<List<DeviceInfo>>

    // GH9 (аналог Report): жалоба на пользователя
    fun reportUser(token: String, toUser: String, reason: String): Result<Unit>

    // AI2 (аналог «в сети» у Telegram): онлайн-статус собеседника —
    // есть ли у него живые WS-соединения и когда был(а) в сети в прошлый раз
    fun checkOnline(token: String, user: String): Result<OnlineStatus>

    // AI2 (аналог приватности «в сети»): не раскрывать свой статус
    fun setPresenceHidden(token: String, hide: Boolean): Result<Unit>

    fun uploadMedia(token: String, data: ByteArray): String

    // Фаза AJ: выгрузка с колбэком прогресса (0..100); реализации без
    // стриминга могут игнорировать колбэк
    fun uploadMedia(token: String, data: ByteArray, onProgress: (Int) -> Unit): String =
        uploadMedia(token, data)
    fun downloadMedia(token: String, id: String): ByteArray
    fun discover(token: String, emailHashes: List<String>): List<Pair<String, String>>
    fun sendTyping(token: String, to: String)
    fun confirmDelivered(token: String, fromUser: String, toDevice: String)
    fun confirmRead(token: String, fromUser: String)

    // GC: создать сессию звонкового пайпа по callId (вызывает звонящий);
    // второй конец подключается WS к /v1/calls/{id}/ws. toUser — кому
    // звонок: сервер пускает в пайп только названного адресата. Фейки в
    // тестах могут не реализовывать — дефолт честно фейлит
    fun createCallSession(token: String, callId: String, toUser: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("call pipe не поддержан"))
}

// AI2: ответ POST /v1/online; lastSeenUnix = 0 — неизвестно
// GH3: устройство аккаунта в списке сессий
data class DeviceInfo(val deviceId: String, val current: Boolean)

open class OnlineStatus(val online: Boolean, val lastSeenUnix: Long) {
    // «был(а) в сети» по времени: null — время неизвестно
    val lastSeenMs: Long? get() = if (lastSeenUnix > 0) lastSeenUnix * 1000L else null
}

open class RelayException(val code: Int, message: String) : IOException(message)

// A8: причины отказа подтверждения линковки
sealed class LinkConfirmResult {
    object Ok : LinkConfirmResult()
    object Expired : LinkConfirmResult() // код не найден или устарел
    object KeyMismatch : LinkConfirmResult() // ключ в коде не совпал с зарегистрированным
    object Conflict : LinkConfirmResult() // устройство закреплено с другим ключом
    class Offline(cause: Exception) : LinkConfirmResult()
}

// Типизированные причины входа (B4): маппинг не завязан на текст
// ответа сервера — «ключи не совпадают» и «ключи недоступны» различимы
class KeysMismatchException : RelayException(409, "keys_mismatch")

class KeysUnavailableException : RelayException(401, "keys_unavailable")

// 2.2: один получатель batch-отправки — свои блобы на каждое устройство
class BatchRecipient(val to: String, val blobs: List<Pair<String, ByteArray>>)

// новое устройство опубликовало ключ: nonce + секрет опроса из QR
class LinkInitResult(val nonce: String, val pollSecret: String)

// Вычерпнутое сообщение с серверным id (S4): id>0 в ack-режиме — по нему
// клиент подтверждает доставку после записи в WAL
class RelayMessage(
    val from: String,
    val kind: String,
    val blob: ByteArray,
    val serverId: Long = 0,
) {
    // деструктуризация (from, kind, blob) для старых вызовов
    operator fun component1() = from
    operator fun component2() = kind
    operator fun component3() = blob
}

// 1.1: страница ack-дрейна; more=true — за страницей ещё есть записи
class DrainPage(
    val messages: List<RelayMessage>,
    val more: Boolean,
)

// Разбор страницы ack-дрейна. Релей недоверенный: одна битая запись
// (нет from_user/blob, не base64) не должна ронять всю страницу и
// стопорить очередь навсегда. Такую запись отдаём «сломанной» (from
// пуст) с сохранённым serverId — приём отбросит её как Broken и
// подтвердит ack, курсор двинется дальше.
internal fun parseDrainPage(body: JSONObject): DrainPage {
    val messages = body.optJSONArray("messages") ?: JSONArray()
    val out = ArrayList<RelayMessage>(messages.length())
    for (i in 0 until messages.length()) {
        val message = messages.optJSONObject(i) ?: continue
        val from = message.optString("from_user", "")
        val blob = try {
            java.util.Base64.getDecoder().decode(message.optString("blob", ""))
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }
        val serverId = message.optLong("id", 0)
        out += if (from.isEmpty() || blob.isEmpty()) {
            RelayMessage("", "chat", ByteArray(0), serverId)
        } else {
            RelayMessage(from, message.optString("kind", "chat"), blob, serverId)
        }
    }
    return DrainPage(out, body.optBoolean("more", false))
}

// потолок страниц полного дрейна: очередь устройства ≤1000 записей,
// страниц больше не бывает — чистая страховка от кривого сервера
private const val DRAIN_MAX_PAGES = 1024

// Реализация поверх OkHttp. Один общий клиент на процесс (Фаза AA5:
// клиенты — в NetClients, прокси применяется ко всему трафику): пулы
// соединений и потоки не плодятся с каждым экземпляром.
class OkHttpRelayApi(private val baseUrl: String) : RelayApi {

    override fun baseUrl(): String = baseUrl.trim().trimEnd('/')

    // точки доступа к общим клиентам процесса
    private val sharedClient get() = chat.client.net.NetClients.api()
    private val mediaClient get() = chat.client.net.NetClients.media()

    // сегменты добавляются по одному и экранируются: peer/media id
    // не сломают путь и не позволят выйти за пределы ветки v1
    private fun url(vararg segments: String): HttpUrl {
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw RelayException(0, "некорректный адрес сервера")
        return base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
    }

    override fun requestCode(email: String): Result<Unit> = try {
        val body = JSONObject().put("email", email.trim().lowercase())
        request("POST", url("v1", "email-code"), body.toString(), null)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    override fun registerWithCode(email: String, code: String, password: String, deviceId: String, pubB64: String): Pair<String, String> {
        val body = JSONObject()
            .put("email", email.trim().lowercase())
            .put("code", code.trim())
            .put("password", password)
            .put("device_id", deviceId)
            .put("pub_spki", pubB64)
        val resp = request("POST", url("v1", "accounts"), body.toString(), null)
        return Pair(resp.getString("token"), resp.getString("user_id"))
    }

    override fun loginByIdentifier(identifier: String, password: String, deviceId: String): Pair<String, String> {
        // S2: логины регистрируются в нижнем регистре — «Alice» не находил
        // аккаунт и ронял вход в 401; email сервер тоже приводит сам
        val body = JSONObject()
            .put("login", identifier.trim().lowercase())
            .put("password", password)
            .put("device_id", deviceId)
        val resp = request("POST", url("v1", "login"), body.toString(), null)
        return Pair(resp.getString("token"), resp.getString("user_id"))
    }

    override fun peerKeys(peer: String): List<Pair<String, ByteArray>> {
        val body = request("GET", url("v1", "keys", peer), null, null)
        val devices = body.getJSONArray("devices")
        val out = ArrayList<Pair<String, ByteArray>>(devices.length())
        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            out.add(
                Pair(
                    device.getString("device_id"),
                    java.util.Base64.getDecoder().decode(device.getString("pub_spki")),
                ),
            )
        }
        return out
    }

    override fun send(token: String, to: String, kind: String, blobs: List<Pair<String, ByteArray>>) {
        val arr = JSONArray()
        for ((deviceId, blob) in blobs) {
            arr.put(
                JSONObject()
                    .put("device_id", deviceId)
                    .put("blob", java.util.Base64.getEncoder().encodeToString(blob)),
            )
        }
        val body = JSONObject()
            .put("to_user", to)
            .put("kind", kind)
            .put("blobs", arr)
        request("POST", url("v1", "messages"), body.toString(), token)
    }

    override fun drainPage(token: String, after: Long): DrainPage {
        // 1.1: одна страница ack-дрейна после курсора; сервер не удаляет
        // до подтверждения — удаление по ack, курсор исключает повторную
        // выдачу той же страницы
        val builder = url("v1", "messages").newBuilder().addQueryParameter("ack", "1")
        if (after > 0) builder.addQueryParameter("after", after.toString())
        val body = request("GET", builder.build(), null, token)
        return parseDrainPage(body)
    }

    override fun ackMessages(token: String, ids: List<Long>) {
        if (ids.isEmpty()) return
        // чанками по 500: тело запроса держим в пределах лимита сервера
        ids.chunked(500).forEach { chunk ->
            val body = JSONObject().put("ids", JSONArray(chunk))
            request("POST", url("v1", "messages", "ack"), body.toString(), token)
        }
    }

    // 2.2: batch — все получатели одним запросом; разбор частичных ошибок
    override fun sendBatch(token: String, kind: String, recipients: List<BatchRecipient>): Map<String, Int> {
        val arr = JSONArray()
        for (r in recipients) {
            val blobs = JSONArray()
            for ((deviceId, blob) in r.blobs) {
                blobs.put(
                    JSONObject()
                        .put("device_id", deviceId)
                        .put("blob", java.util.Base64.getEncoder().encodeToString(blob)),
                )
            }
            arr.put(JSONObject().put("to_user", r.to).put("blobs", blobs))
        }
        val body = JSONObject().put("kind", kind).put("recipients", arr)
        val resp = request("POST", url("v1", "messages", "batch"), body.toString(), token)
        val errors = HashMap<String, Int>()
        val errArr = resp.optJSONArray("errors") ?: return errors
        for (i in 0 until errArr.length()) {
            val e = errArr.getJSONObject(i)
            errors[e.getString("to_user")] = e.optInt("status", 500)
        }
        return errors
    }

    override fun linkInit(deviceId: String, pubB64: String): LinkInitResult {
        val resp = request(
            "POST", url("v1", "link", "init"),
            JSONObject().put("device_id", deviceId).put("pub_spki", pubB64).toString(),
            null,
        )
        return LinkInitResult(resp.getString("nonce"), resp.getString("poll_secret"))
    }

    // 2.4: сначала типизированный код ошибки из JSON, затем старые тексты
    override fun linkConfirm(token: String, nonce: String, pubB64: String): LinkConfirmResult =
        try {
            request(
                "POST", url("v1", "link", "confirm"),
                JSONObject().put("nonce", nonce).put("pub_spki", pubB64).toString(),
                token,
            )
            LinkConfirmResult.Ok
        } catch (e: RelayException) {
            when (errorCodeOf(e)) {
                "key_mismatch" -> LinkConfirmResult.KeyMismatch
                "link_expired" -> LinkConfirmResult.Expired
                "device_conflict" -> LinkConfirmResult.Conflict
                null -> when {
                    // 409: устройство уже закреплено с другим ключом
                    e.code == 409 -> LinkConfirmResult.Conflict
                    // старые серверы отвечают текстом; различаем две причины 400
                    e.code == 400 && e.message?.contains("не совпадает") == true ->
                        LinkConfirmResult.KeyMismatch
                    e.code == 400 -> LinkConfirmResult.Expired
                    else -> LinkConfirmResult.Offline(e)
                }
                else -> LinkConfirmResult.Offline(e)
            }
        } catch (e: IOException) {
            LinkConfirmResult.Offline(e)
        }

    override fun linkPoll(nonce: String, pollSecret: String): Pair<String, String>? {
        val builder = url("v1", "link", nonce).newBuilder()
        builder.addQueryParameter("secret", pollSecret)
        val body = request("GET", builder.build(), null, null)
        if (body.optString("status") != "linked") return null
        return Pair(body.getString("token"), body.getString("user_id"))
    }

    override fun revokeDevice(token: String, deviceId: String): Result<Unit> = try {
        val body = JSONObject().put("device_id", deviceId)
        request("POST", url("v1", "devices", "revoke"), body.toString(), token)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // AH9: типизированные коды ошибок сервера (2.4) поверх Result
    override fun changeEmail(token: String, newEmail: String, code: String): Result<Unit> = try {
        val body = JSONObject()
            .put("new_email", newEmail.trim().lowercase())
            .put("code", code.trim())
        request("POST", url("v1", "account", "email"), body.toString(), token)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    override fun changePassword(token: String, currentPassword: String, newPassword: String): Result<Unit> = try {
        val body = JSONObject()
            .put("current_password", currentPassword)
            .put("new_password", newPassword)
        request("POST", url("v1", "account", "password"), body.toString(), token)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // AI1: удаление аккаунта — токен умирает вместе с аккаунтом
    override fun deleteAccount(token: String, password: String): Result<Unit> = try {
        val body = JSONObject().put("password", password)
        request("POST", url("v1", "account", "delete"), body.toString(), token)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // GH2: облачный пароль; пустая строка снимает 2FA
    override fun setCloudPassword(token: String, currentPassword: String, cloudPassword: String): Result<Unit> = try {
        val body = JSONObject().put("current_password", currentPassword).put("cloudpw", cloudPassword)
        request("POST", url("v1", "account", "cloudpw"), body.toString(), token)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // GH3: свои устройства, current помечает текущее
    override fun listDevices(token: String): Result<List<DeviceInfo>> = try {
        val resp = request("GET", url("v1", "devices"), null, token)
        val arr = resp.getJSONArray("devices")
        val out = (0 until arr.length()).map {
            val d = arr.getJSONObject(it)
            DeviceInfo(d.getString("device_id"), d.optBoolean("current"))
        }
        Result.success(out)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // GH9: жалоба на собеседника
    override fun reportUser(token: String, toUser: String, reason: String): Result<Unit> = try {
        val body = JSONObject().put("to_user", toUser).put("reason", reason.take(500))
        request("POST", url("v1", "report"), body.toString(), token)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // AJ: порог простоя; сервер сам сносит аккаунт по нему
    override fun setAccountInactivity(token: String, password: String, months: Int): Result<Unit> = try {
        val body = JSONObject().put("password", password).put("months", months)
        request("POST", url("v1", "account", "inactivity"), body.toString(), token)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // AI2: онлайн-статус собеседника
    override fun checkOnline(token: String, user: String): Result<OnlineStatus> = try {
        val body = JSONObject().put("user", user)
        val resp = request("POST", url("v1", "online"), body.toString(), token)
        Result.success(OnlineStatus(resp.optInt("online", 0) == 1, resp.optLong("last_seen", 0L)))
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // AI2: приватность своего статуса «в сети»
    override fun setPresenceHidden(token: String, hide: Boolean): Result<Unit> = try {
        val body = JSONObject().put("hide", hide)
        request("POST", url("v1", "presence", "privacy"), body.toString(), token)
        Result.success(Unit)
    } catch (e: RelayException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    }

    override fun uploadMedia(token: String, data: ByteArray): String =
        uploadMedia(token, data) {}

    // Фаза AJ (аналог прогресса выгрузки у Telegram): тело пишется
    // чанками, колбэк тикает процентами; сокет на паузе — всё тот же
    // обычный вызов OkHttp, отмена выгрузки — сбой записи как обычно
    override fun uploadMedia(token: String, data: ByteArray, onProgress: (Int) -> Unit): String {
        val builder = Request.Builder()
            .url(url("v1", "media"))
            .header("Authorization", "Bearer $token")
            .post(ProgressBody(data, "application/octet-stream".toMediaType(), onProgress))
        mediaClient.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw RelayException(
                    response.code,
                    bytes.decodeToString().ifEmpty { "HTTP ${response.code}" },
                )
            }
            return JSONObject(bytes.decodeToString()).getString("media_id")
        }
    }

    override fun downloadMedia(token: String, id: String): ByteArray =
        exchange(mediaClient, "GET", url("v1", "media", id), null, null, token)

    override fun discover(token: String, emailHashes: List<String>): List<Pair<String, String>> {
        val body = JSONObject().put("email_hashes", JSONArray(emailHashes))
        val resp = request("POST", url("v1", "discover"), body.toString(), token)
        val matches = resp.getJSONArray("matches")
        val out = ArrayList<Pair<String, String>>(matches.length())
        for (i in 0 until matches.length()) {
            val m = matches.getJSONObject(i)
            out.add(Pair(m.getString("login"), m.getString("email_hash")))
        }
        return out
    }

    override fun sendTyping(token: String, to: String) {
        try {
            request("POST", url("v1", "typing"), JSONObject().put("to_user", to).toString(), token)
        } catch (_: Exception) {
        }
    }

    override fun confirmDelivered(token: String, fromUser: String, toDevice: String) {
        val body = JSONObject()
            .put("from_user", fromUser)
            .put("to_device", toDevice)
            .toString()
        request("POST", url("v1", "delivered"), body, token)
    }

    override fun confirmRead(token: String, fromUser: String) {
        val body = JSONObject().put("from_user", fromUser).toString()
        request("POST", url("v1", "read"), body, token)
    }

    // GC: сессия пайпа; 409 (лимит/дубликат) — честный failure, звонок не роняем
    override fun createCallSession(token: String, callId: String, toUser: String): Result<Unit> = try {
        request("POST", url("v1", "calls"),
            JSONObject().put("call_id", callId).put("to_user", toUser).toString(), token)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // 2.4: типизированный код ошибки из тела ответа; null — не JSON или кода нет
    private fun errorCodeOf(e: RelayException): String? = try {
        JSONObject(e.message ?: "").optString("error").ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun request(method: String, url: HttpUrl, body: String?, token: String?): JSONObject {
        val bytes = exchange(
            sharedClient, method, url,
            body?.encodeToByteArray(), "application/json", token,
        )
        val text = bytes.decodeToString()
        if (text.isEmpty()) return JSONObject()
        // A8: HTML-заглушка прокси/ошибка шлюза — это сбой сервера, а не
        // JSONException у вызывающего
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw RelayException(502, "некорректный ответ сервера")
        }
    }

    // HTTP-обмен байтовым телом; тело и ответ — произвольные байты
    private fun exchange(
        client: OkHttpClient,
        method: String,
        url: HttpUrl,
        body: ByteArray?,
        contentType: String?,
        token: String?,
    ): ByteArray {
        val builder = Request.Builder().url(url)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val media = (contentType ?: "application/json").toMediaType()
        builder.method(
            method,
            when {
                body != null -> body.toRequestBody(media)
                method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(media)
                else -> null
            },
        )
        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw RelayException(
                    response.code,
                    bytes.decodeToString().ifEmpty { "HTTP ${response.code}" },
                )
            }
            return bytes
        }
    }
}

// Фаза AJ: тело выгрузки с подсчётом отправленных байтов; колбэк
// дёргается раз в процент, не раз в чанк
private class ProgressBody(
    private val data: ByteArray,
    private val contentType: MediaType,
    private val onProgress: (Int) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = data.size.toLong()

    override fun writeTo(sink: okio.BufferedSink) {
        val out = sink.outputStream()
        var sent = 0
        var reported = -1
        val chunk = 64 * 1024
        var i = 0
        while (i < data.size) {
            val n = minOf(chunk, data.size - i)
            out.write(data, i, n)
            sent += n
            // шлём проценты, но не чаще раза на процент
            val percent = (sent * 100L / data.size).toInt().coerceIn(1, 100)
            if (percent != reported) {
                reported = percent
                onProgress(percent)
            }
            i += n
        }
        out.flush()
    }
}
