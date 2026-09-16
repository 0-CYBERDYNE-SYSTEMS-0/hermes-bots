package ai.hermes.bots.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Model verification health (MODEL-UX-PUNCHLIST.md rev 2 / R2, R10): the result of a real
 * end-to-end test turn against a bot's SAVED model pin, cached per connection+provider+model
 * so the editor can badge "verified working" vs a humane failure reason.
 */
enum class HealthState { WORKING, FAILED, TESTING, UNTESTED }

@Serializable
data class HealthEntry(
    val state: HealthState,
    val checkedAtMs: Long,
    val latencyMs: Long? = null,
    /** Humane failure reason (FAILED) or note (UNTESTED, e.g. "gateway dropped"). */
    val reason: String? = null,
)

private val Context.modelHealthStore: androidx.datastore.core.DataStore<Preferences> by
preferencesDataStore(name = "model_health")

object ModelHealth {

    /** Stable store key: connection UUID | provider ("" when inherited) | model id. */
    fun keyFor(connectionId: String, provider: String?, model: String?): String {
        val p = provider?.trim().orEmpty()
        val m = model?.trim().orEmpty()
        return "$connectionId|$p|$m"
    }

    /**
     * Classify a `message.complete` payload (PROTOCOL.md §5.1) into a health result.
     *
     * R2: `error_surface.code` on a completion carries the FailoverReason vocabulary
     * (`agent/error_surface.py:27-46,155`: auth, auth_permanent, billing,
     * billing_unverified, content_policy_blocked, provider_policy_blocked, model_not_found,
     * format_error, ssl_cert_verification, timeout, stream_drop, unknown, disk_full) plus
     * the hand-built runtime `agent_init_failed` (`tui_gateway/methods_prompt.py:481-483`).
     * The bot/relay `failure_reason` strings (tools/bot_failure_reasons.py) are a fallback
     * sniff only. Free text sniffing is last-resort.
     */
    fun classify(
        status: String?,
        error: String?,
        errorSurface: JsonObject?,
        failureReason: String?,
    ): Pair<HealthState, String?> {
        val failed = status == "error" || !error.isNullOrBlank()
        if (!failed) return HealthState.WORKING to null

        val layer = (errorSurface?.get("layer") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val code = (errorSurface?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val raw = error.orEmpty()
        val lower = raw.lowercase()

        // agent_init_failed needs its text sniffed: the classifier can't see the agent
        // build error's own surface, so "Unknown provider 'x'" / config problems land here.
        if (code == "agent_init_failed" || layer == "runtime") {
            return HealthState.FAILED to when {
                "unknown provider" in lower && "custom:" in lower ->
                    "This provider spelling only works for the gateway's own default bot — pick one from the list"
                "unknown provider" in lower ->
                    "This gateway doesn't have that provider — pick one from the list"
                "invalid model" in lower || "expected format" in lower || "model_id" in lower ->
                    "The endpoint rejected that model id"
                "api key" in lower || "apikey" in lower || "unauthorized" in lower || "auth" in lower ->
                    "Key rejected or missing on the gateway"
                "quota" in lower || "credit" in lower || "balance" in lower || "insufficient" in lower ->
                    "Out of credits or over limit on the gateway's key"
                else -> "The bot failed to start on the gateway"
            }
        }

        return HealthState.FAILED to when (code) {
            "auth", "auth_permanent" -> "Key rejected or missing on the gateway"
            "billing", "billing_unverified" -> "Out of credits or billing blocked"
            "model_not_found", "format_error" -> "The endpoint rejected that model id"
            "content_policy_blocked", "provider_policy_blocked" -> "The model refused (provider policy)"
            "timeout", "stream_drop" -> "Connection to the model dropped — try again"
            "ssl_cert_verification" -> "Endpoint certificate problem on the gateway"
            "disk_full" -> "The gateway's disk is full"
            else -> when {
                failureReason == "provider_auth_or_access" -> "Key rejected or missing on the gateway"
                failureReason == "provider_quota_limit" || failureReason == "provider_rate_limit" ->
                    "Out of credits or over limit on the gateway's key"
                failureReason == "missing_config" -> "Provider not configured on the gateway"
                failureReason == "model_unavailable" -> "That model is unavailable right now"
                "invalid model" in lower || "expected format" in lower || "model_id" in lower ->
                    "The endpoint rejected that model id"
                "429" in lower || "quota" in lower || "credit" in lower || "balance" in lower ->
                    "Out of credits or over limit on the gateway's key"
                "401" in lower || "unauthorized" in lower || "api key" in lower -> "Key rejected or missing on the gateway"
                else -> friendlyRaw(raw)
            }
        }
    }

    /** Last-resort: show a trimmed raw error rather than nothing. */
    private fun friendlyRaw(raw: String): String {
        val one = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "The model did not respond"
        return if (one.length > 140) one.take(137) + "…" else one
    }
}

/** DataStore-backed cache keyed by [ModelHealth.keyFor]; LRU-capped (R10). */
class ModelHealthStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val keyEntries = stringPreferencesKey("health_entries_json")

    val entries: Flow<Map<String, HealthEntry>> = context.modelHealthStore.data
        .map { prefs -> decode(prefs[keyEntries]) }

    fun entry(connectionId: String, provider: String?, model: String?): Flow<HealthEntry?> =
        entries.map { it[ModelHealth.keyFor(connectionId, provider, model)] }

    suspend fun record(
        connectionId: String,
        provider: String?,
        model: String?,
        state: HealthState,
        latencyMs: Long? = null,
        reason: String? = null,
    ) {
        val k = ModelHealth.keyFor(connectionId, provider, model)
        context.modelHealthStore.edit { prefs ->
            val map = decode(prefs[keyEntries]).toMutableMap()
            map.remove(k)
            map[k] = HealthEntry(
                state = state,
                checkedAtMs = System.currentTimeMillis(),
                latencyMs = latencyMs,
                reason = reason,
            )
            // LRU cap: drop oldest beyond the limit.
            while (map.size > MAX_ENTRIES) {
                val oldest = map.entries.minByOrNull { it.value.checkedAtMs }?.key ?: break
                map.remove(oldest)
            }
            prefs[keyEntries] = json.encodeToString(map)
        }
    }

    /** Optimistic in-flight marker while a verification turn runs. */
    suspend fun markTesting(connectionId: String, provider: String?, model: String?) =
        record(connectionId, provider, model, HealthState.TESTING)

    suspend fun clear(connectionId: String, provider: String?, model: String?) {
        val k = ModelHealth.keyFor(connectionId, provider, model)
        context.modelHealthStore.edit { prefs ->
            val map = decode(prefs[keyEntries]).toMutableMap()
            map.remove(k)
            prefs[keyEntries] = json.encodeToString(map)
        }
    }

    /** Best-effort prune of keys belonging to deleted connections (R10). */
    suspend fun pruneExcept(keepConnectionIds: Set<String>) {
        context.modelHealthStore.edit { prefs ->
            val map = decode(prefs[keyEntries]).filterKeys { key ->
                key.substringBefore('|') in keepConnectionIds
            }
            prefs[keyEntries] = json.encodeToString(map)
        }
    }

    private fun decode(text: String?): Map<String, HealthEntry> =
        text?.let {
            runCatching { json.decodeFromString<Map<String, HealthEntry>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()

    companion object {
        const val MAX_ENTRIES = 200
    }
}
