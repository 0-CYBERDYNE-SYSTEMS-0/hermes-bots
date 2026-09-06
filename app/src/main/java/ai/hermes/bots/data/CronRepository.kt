package ai.hermes.bots.data

import ai.hermes.bots.protocol.Auth
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.ProtocolException
import ai.hermes.bots.protocol.SocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

data class CronJob(
    val connectionId: String,
    val id: String,
    val name: String,
    val enabled: Boolean,
    val scheduleText: String,
    val prompt: String,
)

/**
 * Routines via REST /api/cron/jobs (desktop parity: cron is REST, not RPC —
 * PROTOCOL.md §7). Re-polls on the `cron.changed` push with a 60 s backstop.
 */
class CronRepository(
    private val manager: GatewayManager,
    private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder().cookieJar(Auth.COOKIE_JAR).build()
    private val json = Json { ignoreUnknownKeys = true }

    private val _jobs = MutableStateFlow<Map<String, List<CronJob>>>(emptyMap())
    val jobs: StateFlow<Map<String, List<CronJob>>> = _jobs

    private val pollJobs = mutableMapOf<String, Job>()
    private var syncJob: Job? = null

    fun start() {
        if (syncJob != null) return
        syncJob = scope.launch {
            manager.live.collect { live ->
                pollJobs.keys.filterNot { it in live }.forEach {
                    pollJobs.remove(it)?.cancel()
                    _jobs.update { m -> m - it }
                }
                live.forEach { (id, conn) ->
                    if (pollJobs[id] == null) pollJobs[id] = scope.launch { pollLoop(id, conn) }
                }
            }
        }
    }

    suspend fun refresh(connectionId: String) {
        val conn = manager.live.value[connectionId] ?: return
        runCatching { pollOnce(connectionId, conn) }
    }

    private suspend fun pollLoop(connectionId: String, conn: ConnectionLive) {
        while (true) {
            val ready = withTimeoutOrNull(60_000) {
                conn.gateway.state.first { it is SocketState.Ready }
            }
            if (ready != null) {
                runCatching { pollOnce(connectionId, conn) }
                    .onFailure { android.util.Log.e("CronRepo", "poll failed", it) }
            }
            withTimeoutOrNull(60_000) {
                conn.gateway.events.first { it.type == Catalog.EVENT_CRON_CHANGED }
                delay(300)
            }
        }
    }

    private suspend fun pollOnce(connectionId: String, conn: ConnectionLive) {
        val rows = call(conn.record, "GET", Catalog.REST_CRON_JOBS + "?profile=all", null)
        val jobs = (rows as? JsonArray)?.mapNotNull { parseJob(connectionId, it) }.orEmpty()
        _jobs.update { it + (connectionId to jobs) }
    }

    suspend fun listProfile(connectionId: String, profile: String): List<CronJob> {
        val conn = manager.live.value[connectionId] ?: throw IllegalStateException("connection not live")
        val q = "?profile=" + java.net.URLEncoder.encode(profile, "UTF-8")
        val rows = call(conn.record, "GET", Catalog.REST_CRON_JOBS + q, null)
        return (rows as? JsonArray)?.mapNotNull { parseJob(connectionId, it) }.orEmpty()
    }

    suspend fun create(connectionId: String, profile: String?, prompt: String, schedule: String, name: String): Boolean {
        val conn = manager.live.value[connectionId] ?: throw IllegalStateException("connection not live")
        val body = buildJsonObject {
            put("prompt", prompt)
            put("schedule", schedule)
            if (name.isNotBlank()) put("name", name)
            put("deliver", "local")
        }
        val q = if (profile.isNullOrBlank()) "" else "?profile=" + URLEncoder.encode(profile, "UTF-8")
        call(conn.record, "POST", Catalog.REST_CRON_JOBS + q, body.toString())
        return true
    }

    suspend fun update(connectionId: String, jobId: String, updates: JsonObject): Boolean {
        val conn = manager.live.value[connectionId] ?: throw IllegalStateException("connection not live")
        call(conn.record, "PUT", String.format(Catalog.REST_CRON_JOB, jobId), buildJsonObject { put("updates", updates) }.toString())
        return true
    }

    suspend fun delete(connectionId: String, jobId: String): Boolean {
        val conn = manager.live.value[connectionId] ?: throw IllegalStateException("connection not live")
        call(conn.record, "DELETE", String.format(Catalog.REST_CRON_JOB, jobId), null)
        return true
    }

    suspend fun setEnabled(connectionId: String, jobId: String, enabled: Boolean): Boolean {
        val conn = manager.live.value[connectionId] ?: throw IllegalStateException("connection not live")
        call(conn.record, "POST", String.format(Catalog.REST_CRON_JOB, jobId) + "/" + if (enabled) "resume" else "pause", null)
        return true
    }

    private suspend fun call(record: ConnectionRecord, method: String, path: String, body: String?): JsonElement =
        withContext(Dispatchers.IO) {
            val attempt = {
                val builder = Request.Builder()
                    .url(record.baseUrl + path)
                    .method(method, if (body != null) body.toRequestBody("application/json".toMediaType()) else null)
                Auth.restHeaders(record.auth).forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ProtocolException("cron $method $path -> ${resp.code}: ${text.take(200)}")
                    if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
                }
            }
            try {
                attempt()
            } catch (e: ProtocolException) {
                // Gated (BasicAuth) gateways answer 401 when the cookie session expired —
                // re-login once and retry (the ws-ticket path does the same in Auth.mintTicket).
                val gatedRetry = (resp401or403(e)) && record.auth is GatewayAuth.BasicAuth
                if (!gatedRetry) throw e
                Auth.ensureGatedSession(client, record.baseUrl, record.auth as GatewayAuth.BasicAuth)
                attempt()
            } catch (e: Exception) {
                throw ProtocolException("cron $method $path failed: ${e.message}")
            }
        }

    private fun resp401or403(e: ProtocolException): Boolean =
        e.message?.contains("-> 401") == true || e.message?.contains("-> 403") == true

    companion object {
        fun parseJob(connectionId: String, element: JsonElement): CronJob? {
            val o = element as? JsonObject ?: return null
            fun str(key: String): String? =
                (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = str("id") ?: return null
            val schedule = when (val s = o["schedule"]) {
                is JsonObject -> {
                    val expr = (s["expr"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val kind = (s["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    expr ?: kind ?: "schedule"
                }
                is JsonPrimitive -> if (s.isString) s.content else s.content
                else -> "schedule"
            }
            return CronJob(
                connectionId = connectionId,
                id = id,
                name = str("name").orEmpty().ifBlank { id },
                enabled = (o["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
                scheduleText = schedule,
                prompt = str("prompt").orEmpty(),
            )
        }
    }
}
