package chat.crypto

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

// Кросс-стек smoke: Kotlin-крипто ↔ Go-релей с авторизацией.
// Активируется переменной CHAT_RELAY_URL:
// CHAT_RELAY_URL=http://127.0.0.1:18080 ./gradlew jvmTest --rerun-tasks
class RelaySmokeTest {
    private val relay = System.getenv("CHAT_RELAY_URL")
    private val http = HttpClient.newHttpClient()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    private fun unique(prefix: String) = "$prefix-${System.nanoTime()}"

    @Test
    fun fullFlowThroughRelayWithAuth() {
        assumeTrue("CHAT_RELAY_URL не задан — smoke пропущен", relay != null)
        val base = relay!!.trimEnd('/')
        val aliceLogin = unique("alice")
        val bobLogin = unique("bob")
        val alice = E2EFactory.generate()
        val bob = E2EFactory.generate()

        val aliceToken = register(base, aliceLogin, alice.publicKey())
        val bobToken = register(base, bobLogin, bob.publicKey())

        // ключ получателя берём только с сервера, как в бою
        val bobFromServer = fetchFirstKey(base, bobLogin)
        assertEquals(bob.publicKey().toList(), bobFromServer.toList())

        val message = "привет через релей".encodeToByteArray()
        val blob = alice.seal(bobFromServer, message)
        postMessage(base, aliceToken, bobLogin, blob)

        val drained = drain(base, bobToken)
        assertEquals(1, drained.size)
        assertEquals(message.toList(), bob.open(drained.first()).toList())
        assertEquals(0, drain(base, bobToken).size, "дрейн должен очищать очередь")
    }

    @Test
    fun tofuAndUnauthorizedRejected() {
        assumeTrue("CHAT_RELAY_URL не задан — smoke пропущен", relay != null)
        val base = relay!!.trimEnd('/')
        val carol = E2EFactory.generate()
        val carolLogin = unique("carol")
        val token = register(base, carolLogin, carol.publicKey())

        // подмена ключа закреплённого устройства — 409
        val attacker = E2EFactory.generate()
        val status = http.send(
            HttpRequest.newBuilder()
                .uri(URI("$base/v1/keys/phone"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(pubBody(attacker.publicKey())))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode()
        assertEquals(409, status)

        // без токена — 401
        val anonStatus = http.send(
            HttpRequest.newBuilder()
                .uri(URI("$base/v1/keys/phone"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(pubBody(attacker.publicKey())))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode()
        assertEquals(401, anonStatus)
    }

    // --- HTTP-хелперы: минимум парсинга, без JSON-библиотек ---

    private fun pubBody(pub: ByteArray): String =
        """{"pub_spki":"${encoder.encodeToString(pub)}"}"""

    // email-регистрация как в приложении: логин = префикс email;
    // dev-релей (без CHAT_RESEND_KEY) возвращает код прямо в ответе
    private fun register(base: String, login: String, pub: ByteArray): String {
        val email = "$login@e2e.local"
        val code = emailCode(base, email)
        val body = """{"email":"$email","code":"$code","password":"secret123"""" +
            ""","device_id":"phone","pub_spki":"${encoder.encodeToString(pub)}"}"""
        val request = HttpRequest.newBuilder()
            .uri(URI("$base/v1/accounts"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "регистрация $login")
        return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(response.body())!!.groupValues[1]
    }

    private fun emailCode(base: String, email: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI("$base/v1/email-code"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"email":"$email"}"""))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "email-код для $email")
        return Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(response.body())?.groupValues?.get(1)
            ?: error("код не в ответе — релей с CHAT_RESEND_KEY? smoke только против dev-релея")
    }

    private fun fetchFirstKey(base: String, user: String): ByteArray {
        val request = HttpRequest.newBuilder().uri(URI("$base/v1/keys/$user")).GET().build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        val match = Regex("\"pub_spki\"\\s*:\\s*\"([^\"]+)\"").find(body)
            ?: error("нет ключей в ответе сервера")
        return decoder.decode(match.groupValues[1])
    }

    private fun postMessage(base: String, token: String, to: String, blob: ByteArray) {
        // fan-out контракт: блоб для каждого устройства получателя
        val body = """{"to_user":"$to","blobs":[{"device_id":"phone","blob":"${encoder.encodeToString(blob)}"}]}"""
        val request = HttpRequest.newBuilder()
            .uri(URI("$base/v1/messages"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    private fun drain(base: String, token: String): List<ByteArray> {
        val request = HttpRequest.newBuilder()
            .uri(URI("$base/v1/messages"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return Regex("\"blob\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(body)
            .map { decoder.decode(it.groupValues[1]) }
            .toList()
    }
}
