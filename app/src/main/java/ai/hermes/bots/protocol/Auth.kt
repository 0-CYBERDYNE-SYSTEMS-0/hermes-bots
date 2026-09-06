package ai.hermes.bots.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Gateway auth mode (DECISIONS.md #4: token AND basic; PROTOCOL.md §2). */
@Serializable
sealed interface GatewayAuth {
    @Serializable
    @SerialName("token")
    data class TokenAuth(val token: String) : GatewayAuth

    @Serializable
    @SerialName("basic")
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
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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

    /** REST headers for the auth mode (PROTOCOL.md §7). BasicAuth also rides the cookie jar. */
    fun restHeaders(auth: GatewayAuth): Map<String, String> = when (auth) {
        is GatewayAuth.TokenAuth -> mapOf(Catalog.HEADER_SESSION_TOKEN to auth.token)
        is GatewayAuth.BasicAuth -> mapOf("Authorization" to "Basic " + basicCredentials(auth))
    }

    private fun basicCredentials(auth: GatewayAuth.BasicAuth): String =
        Base64.getEncoder().encodeToString("${auth.username}:${auth.password}".toByteArray())

    /**
     * In-memory cookie jar shared by every gated (BasicAuth) connection. Real gated gateways
     * reject per-request Basic headers with 401 "no_cookie" — the session comes from
     * POST /auth/password-login and rides cookies (PROTOCOL.md §2 runtime finding, 2026-09-06).
     */
    val COOKIE_JAR: CookieJar = object : CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { c ->
                store.getOrPut(url.host) { java.util.concurrent.ConcurrentHashMap() }[c.name] = c
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host]?.values?.filter { it.matches(url) }.orEmpty()
    }

    /** Cached password-provider name per baseUrl (GET /api/auth/providers, public). */
    private val passwordProviders = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun passwordProviderName(client: OkHttpClient, baseUrl: String): String =
        passwordProviders[baseUrl] ?: run {
            val name = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(baseUrl + Catalog.REST_AUTH_PROVIDERS).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use "basic"
                    val obj = runCatching {
                        json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    }.getOrNull()
                    val providers = obj?.get("providers") as? JsonArray
                    providers?.firstOrNull { (it as? JsonObject)?.get("supports_password") == JsonPrimitive(true) }
                        ?.let { (it as? JsonObject)?.get("name") as? JsonPrimitive }?.content
                        ?: "basic"
                }
            }
            passwordProviders[baseUrl] = name
            name
        }

    /**
     * Establish the cookie session for a gated gateway: POST /auth/password-login
     * {provider, username, password, next:""} → session cookies land in COOKIE_JAR.
     */
    suspend fun ensureGatedSession(client: OkHttpClient, baseUrl: String, auth: GatewayAuth.BasicAuth) {
        val provider = passwordProviderName(client, baseUrl)
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("provider", JsonPrimitive(provider))
                put("username", JsonPrimitive(auth.username))
                put("password", JsonPrimitive(auth.password))
                put("next", JsonPrimitive(""))
            },
        )
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl + Catalog.REST_PASSWORD_LOGIN)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw ProtocolException("password-login ${resp.code}: ${resp.body?.string().orEmpty().take(200)}")
                }
            }
        }
    }

    private suspend fun requestTicket(client: OkHttpClient, baseUrl: String, auth: GatewayAuth.BasicAuth): String =
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

    /**
     * Mint a single-use WS ticket (30 s TTL). Gated servers answer the first ticket call with
     * 401 until the cookie session exists — log in with the stored credentials and retry once.
     */
    suspend fun mintTicket(client: OkHttpClient, baseUrl: String, auth: GatewayAuth.BasicAuth): String =
        try {
            requestTicket(client, baseUrl, auth)
        } catch (e: ProtocolException) {
            if (e.message?.contains(" 401") == true || e.message?.contains(" 403") == true) {
                ensureGatedSession(client, baseUrl, auth)
                requestTicket(client, baseUrl, auth)
            } else {
                throw e
            }
        }
}
