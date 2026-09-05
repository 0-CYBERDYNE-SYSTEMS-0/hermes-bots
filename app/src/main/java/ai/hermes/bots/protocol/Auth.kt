package ai.hermes.bots.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64

/** Gateway auth mode (DECISIONS.md #4: token AND basic; PROTOCOL.md §2). */
sealed interface GatewayAuth {
    data class TokenAuth(val token: String) : GatewayAuth
    data class BasicAuth(val username: String, val password: String) : GatewayAuth
}

/** Result of GET /api/status — the first call when adding a gateway (PROTOCOL.md §7, §8). */
data class GatewayProbe(
    val reachable: Boolean,
    val authRequired: Boolean? = null,
    val httpCode: Int = 0,
    val version: String? = null,
    val error: String? = null,
) {
    /** Token mode servers answer auth_required:false and accept ?token= on the upgrade. */
    val tokenMode: Boolean get() = reachable && authRequired == false
}

object Auth {
    private val json = Json { ignoreUnknownKeys = true }

    /** Scheme-less host[:port] → prefix http://; append default port when absent (PROTOCOL.md §8.1). */
    fun normalizeBaseUrl(input: String, defaultPort: Int = 9119): String {
        var s = input.trim().trimEnd('/')
        require(s.isNotEmpty()) { "empty base url" }
        if (!s.contains("://")) s = "http://$s"
        val authority = s.substringAfter("://").substringBefore('/')
        return if (authority.contains(':')) s else "$s:$defaultPort"
    }

    fun wsUrl(baseUrl: String): String =
        baseUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + Catalog.WS_PATH

    fun wsUrlWithAuth(baseUrl: String, token: String): String =
        wsUrl(baseUrl) + "?token=" + encode(token)

    fun wsUrlWithTicket(baseUrl: String, ticket: String): String =
        wsUrl(baseUrl) + "?ticket=" + encode(ticket)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    suspend fun probe(client: OkHttpClient, baseUrl: String): GatewayProbe =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(baseUrl + Catalog.REST_STATUS).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        GatewayProbe(reachable = true, httpCode = resp.code, error = "status ${resp.code}")
                    } else {
                        val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                        GatewayProbe(
                            reachable = true,
                            authRequired = (obj["auth_required"] as? JsonPrimitive)
                                ?.content?.toBooleanStrictOrNull(),
                            httpCode = resp.code,
                            version = (obj["version"] as? JsonPrimitive)
                                ?.takeIf { it.isString }?.content,
                        )
                    }
                }
            } catch (e: Exception) {
                GatewayProbe(reachable = false, error = e.message ?: e.javaClass.simpleName)
            }
        }

    /** POST /api/auth/ws-ticket with Basic auth → single-use ticket, 30 s TTL (PROTOCOL.md §2). */
    suspend fun mintTicket(client: OkHttpClient, baseUrl: String, auth: GatewayAuth.BasicAuth): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl + Catalog.REST_WS_TICKET)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Basic " + basicCredentials(auth))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ProtocolException("ws-ticket ${resp.code}: ${body.take(200)}")
                }
                (json.parseToJsonElement(body).jsonObject["ticket"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                    ?: throw ProtocolException("ws-ticket response missing ticket")
            }
        }

    /** REST headers for the auth mode (PROTOCOL.md §7). */
    fun restHeaders(auth: GatewayAuth): Map<String, String> = when (auth) {
        is GatewayAuth.TokenAuth -> mapOf(Catalog.HEADER_SESSION_TOKEN to auth.token)
        is GatewayAuth.BasicAuth -> mapOf("Authorization" to "Basic " + basicCredentials(auth))
    }

    private fun basicCredentials(auth: GatewayAuth.BasicAuth): String =
        Base64.getEncoder().encodeToString("${auth.username}:${auth.password}".toByteArray())
}
