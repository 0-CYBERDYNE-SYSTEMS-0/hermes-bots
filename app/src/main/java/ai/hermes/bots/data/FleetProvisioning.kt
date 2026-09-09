package ai.hermes.bots.data

import ai.hermes.bots.protocol.GatewayAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Parsed `hermesbots://add-gateway?...` payload (FLEET-CONNECT-SPEC B1a). */
data class AddGatewayParams(
    val url: String, // normalized
    val label: String?,
    val username: String?, // present → gated/basic mode
    val password: String?, // the URI's `token` param, used as the gated password
    val token: String?, // present (no user) → session-token mode
)

/** What the pre-filled "Add/Edit gateway" dialog edits (existing connection or a new one). */
data class GatewayDraft(
    val existingId: String?, // non-null → the dialog edits the existing connection
    val label: String,
    val baseUrl: String, // normalized
    val useBasic: Boolean,
    val username: String,
    val password: String,
    val token: String,
)

/** One gateway in an exported/imported fleet config (secrets included per spec). */
@Serializable
data class FleetConfigEntry(
    val name: String,
    val url: String,
    val token: String? = null,
    val user: String? = null,
    val password: String? = null,
)

data class FleetMergeResult(val added: Int, val skipped: Int)

/**
 * Fleet connection provisioning (FLEET-CONNECT-SPEC B1/B8): deep-link parsing, save-path
 * URL normalization, and the JSON fleet-config export/import format. The codec pieces are
 * pure and unit-tested; the coordinator wraps ConnectionRepository for the UI.
 */
object FleetProvisioning {

    const val SCHEME = "hermesbots"
    const val HOST = "add-gateway"

    /**
     * B8 save-path normalization: trim whitespace, strip trailing slashes, prefix http://
     * onto scheme-less host:port. Does NOT invent a default port (the caller knows it).
     */
    fun normalizeBaseUrl(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "http://$s"
        return s
    }

    /**
     * Parses `hermesbots://add-gateway?url=…&user=…&token=…[&name=…]`. Pure Kotlin (no
     * android.net.Uri) so it unit-tests on the JVM. `+` is treated literally, not as
     * space — gateway secrets are base64-ish and '+' is far likelier than a literal space.
     */
    fun parseAddGatewayUri(uri: String): AddGatewayParams? {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd <= 0) return null
        if (uri.substring(0, schemeEnd) != SCHEME) return null
        val rest = uri.substring(schemeEnd + 3)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        if (rest.substring(0, hostEnd) != HOST) return null
        val qStart = rest.indexOf('?')
        if (qStart < 0) return null
        val params = rest.substring(qStart + 1)
            .split('&')
            .filter { it.contains('=') }
            .map { it.substringBefore('=') to decode(it.substringAfter('=')) }
            .toMap()
        val url = normalizeBaseUrl(params["url"].orEmpty())
        if (url.isEmpty()) return null
        val user = params["user"]?.trim()?.takeIf { it.isNotEmpty() }
        val secret = params["token"]?.trim()?.takeIf { it.isNotEmpty() }
        return AddGatewayParams(
            url = url,
            label = params["name"]?.trim()?.takeIf { it.isNotEmpty() },
            username = user,
            password = secret?.takeIf { user != null },
            token = secret?.takeIf { user == null },
        )
    }

    /** Builds the provisioning URI (mirror of the provisioning script's --qr output). */
    fun addGatewayUri(url: String, username: String?, secret: String, label: String?): String {
        val q = buildList {
            add("url=" + encode(normalizeBaseUrl(url)))
            if (!username.isNullOrBlank()) add("user=" + encode(username))
            add("token=" + encode(secret))
            if (!label.isNullOrBlank()) add("name=" + encode(label))
        }.joinToString("&")
        return "$SCHEME://$HOST?$q"
    }

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

/** Pure JSON codec for the Settings "Fleet config" export/import (B1c). Unit-tested. */
object FleetConfigCodec {

    @Serializable
    private data class Envelope(
        val app: String = "hermes-bots",
        val type: String = "fleet-config",
        val version: Int = 1,
        val gateways: List<FleetConfigEntry> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun export(records: List<ConnectionRecord>): String =
        json.encodeToString(Envelope(gateways = records.map { it.toEntry() }))

    /** Accepts the envelope object or a bare array; throws IllegalArgumentException on junk. */
    fun parse(text: String): List<FleetConfigEntry> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Nothing to import — the text is empty")
        val root = runCatching { json.parseToJsonElement(trimmed) }
            .getOrElse { throw IllegalArgumentException("That isn't valid JSON") }
        val array = when {
            root is kotlinx.serialization.json.JsonObject -> root["gateways"] as? kotlinx.serialization.json.JsonArray
            root is kotlinx.serialization.json.JsonArray -> root
            else -> null
        } ?: throw IllegalArgumentException("No gateways found in that JSON")
        return array.mapIndexedNotNull { i, el ->
            val obj = el as? kotlinx.serialization.json.JsonObject
                ?: throw IllegalArgumentException("Entry ${i + 1} isn't an object")
            val url = FleetProvisioning.normalizeBaseUrl(
                (obj["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
            )
            if (url.isEmpty()) throw IllegalArgumentException("Entry ${i + 1} has no address")
            FleetConfigEntry(
                name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: url.substringAfter("://").substringBefore(':'),
                url = url,
                token = obj.string("token"),
                user = obj.string("user") ?: obj.string("username"),
                password = obj.string("password"),
            )
        }
    }

    /** Entries → records with fresh ids; auth mode from fields (user+password → basic). */
    fun toRecords(entries: List<FleetConfigEntry>): List<ConnectionRecord> =
        entries.map { e ->
            ConnectionRecord(
                id = UUID.randomUUID().toString(),
                label = e.name,
                baseUrl = FleetProvisioning.normalizeBaseUrl(e.url),
                auth = when {
                    !e.user.isNullOrBlank() || !e.password.isNullOrBlank() ->
                        GatewayAuth.BasicAuth(e.user.orEmpty(), e.password.orEmpty())
                    else -> GatewayAuth.TokenAuth(e.token.orEmpty())
                },
            )
        }

    /**
     * Merge for import: skip records whose normalized URL already exists (in the fleet or
     * earlier in the same batch). Returns the records to add and the humane counts.
     */
    fun merge(
        existing: List<ConnectionRecord>,
        incoming: List<ConnectionRecord>,
    ): Pair<List<ConnectionRecord>, FleetMergeResult> {
        val seen = existing.map { FleetProvisioning.normalizeBaseUrl(it.baseUrl) }.toMutableSet()
        val toAdd = mutableListOf<ConnectionRecord>()
        var skipped = 0
        incoming.forEach { rec ->
            val url = FleetProvisioning.normalizeBaseUrl(rec.baseUrl)
            if (url in seen) {
                skipped += 1
            } else {
                seen += url
                toAdd += rec
            }
        }
        return toAdd to FleetMergeResult(added = toAdd.size, skipped = skipped)
    }

    private fun ConnectionRecord.toEntry(): FleetConfigEntry = when (val a = auth) {
        is GatewayAuth.TokenAuth -> FleetConfigEntry(name = label, url = baseUrl, token = a.token)
        is GatewayAuth.BasicAuth -> FleetConfigEntry(
            name = label,
            url = baseUrl,
            user = a.username,
            password = a.password,
        )
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * Graph-scoped coordinator: holds the one-shot pre-fill draft driven by a deep link (B1a)
 * and the fleet-config import path (B1c). Pure logic lives in the objects above.
 */
class FleetProvisioningCoordinator(private val repo: ConnectionRepository) {

    private val _draft = MutableStateFlow<GatewayDraft?>(null)
    val draft: StateFlow<GatewayDraft?> = _draft

    /** Deep link → pre-fill draft. A matching normalized URL pre-fills the EDIT case. */
    suspend fun fromDeepLink(uri: String): GatewayDraft? {
        val params = FleetProvisioning.parseAddGatewayUri(uri) ?: return null
        val existing = repo.connections.first()
            .firstOrNull { FleetProvisioning.normalizeBaseUrl(it.baseUrl) == params.url }
        val draft = when (val prior = existing?.auth) {
            null -> GatewayDraft(
                existingId = null,
                label = params.label.orEmpty(),
                baseUrl = params.url,
                useBasic = params.username != null,
                username = params.username.orEmpty(),
                password = params.password.orEmpty(),
                token = params.token.orEmpty(),
            )
            is GatewayAuth.BasicAuth -> GatewayDraft(
                existingId = existing.id,
                label = params.label ?: existing.label,
                baseUrl = params.url,
                useBasic = params.username != null || params.token == null,
                username = params.username ?: prior.username,
                password = params.password ?: prior.password,
                token = "",
            )
            is GatewayAuth.TokenAuth -> GatewayDraft(
                existingId = existing.id,
                label = params.label ?: existing.label,
                baseUrl = params.url,
                useBasic = params.username != null,
                username = params.username.orEmpty(),
                password = params.password.orEmpty(),
                token = params.token ?: prior.token,
            )
        }
        _draft.value = draft
        return draft
    }

    fun consume() {
        _draft.value = null
    }

    suspend fun save(draft: GatewayDraft) {
        val record = ConnectionRecord(
            id = draft.existingId ?: UUID.randomUUID().toString(),
            label = draft.label.trim().ifEmpty { "Gateway" },
            baseUrl = draft.baseUrl,
            auth = if (draft.useBasic) {
                GatewayAuth.BasicAuth(draft.username.trim(), draft.password)
            } else {
                GatewayAuth.TokenAuth(draft.token.trim())
            },
        )
        repo.upsert(record)
    }

    /** Import merge (B1c): returns the humane counts, e.g. "Added 2, skipped 1". */
    suspend fun importRecords(text: String): FleetMergeResult {
        val entries = FleetConfigCodec.parse(text)
        val incoming = FleetConfigCodec.toRecords(entries)
        val (toAdd, result) = FleetConfigCodec.merge(repo.connections.first(), incoming)
        toAdd.forEach { repo.upsert(it) }
        return result
    }
}
