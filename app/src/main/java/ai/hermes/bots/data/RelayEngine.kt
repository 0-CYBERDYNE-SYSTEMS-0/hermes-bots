package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.RpcException
import ai.hermes.bots.protocol.SocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.logging.Logger

/**
 * Cross-connection A2A relay — the app IS the relay (BOTS-MODE-PARITY.md §8, verbatim timings):
 * roster loop every 60 s pushes the union of OTHER connections' agents to each gateway via
 * bot_relay.roster.sync; drain loop every 30 s (+ immediate, debounced, on
 * bot_relay.outbox.pending) drains each sender's outbox, delivers each envelope on the TARGET
 * connection's socket with a >1320 s budget, then posts bot_relay.reply back on the sender's
 * socket. Gateways whose RPCs are missing (-32601) are marked relayUnsupported and skipped.
 */
class RelayEngine(
    private val manager: GatewayManager,
    private val scope: CoroutineScope,
) {
    private val log = Logger.getLogger("RelayEngine")

    private val loops = mutableMapOf<String, Job>()
    private var syncJob: Job? = null

    /** connectionId -> last synced agent list (for the roster loop). */
    private val agentCache = mutableMapOf<String, List<RelayAgent>>()

    data class RelayAgent(
        val profile: String,
        val handle: String,
        val connectionId: String,
        val connectionLabel: String,
        val title: String,
        val description: String,
    ) {
        fun toParams(): JsonObject = buildJsonObject {
            put("profile", profile)
            put("handle", handle)
            put("connection_id", connectionId)
            put("connection_label", connectionLabel)
            put("title", title)
            put("description", description)
        }
    }

    fun start() {
        if (syncJob != null) return
        syncJob = scope.launch {
            manager.live.collect { live ->
                loops.keys.filterNot { it in live }.forEach { id ->
                    loops.remove(id)?.cancel()
                    agentCache.remove(id)
                }
                live.forEach { (id, conn) ->
                    if (loops[id] == null) loops[id] = scope.launch { relayLoop(id, conn) }
                }
            }
        }
    }

    private suspend fun relayLoop(connectionId: String, conn: ConnectionLive) {
        if (!ensureSupported(connectionId, conn)) return
        val rosterJob = scope.launch { rosterLoop(connectionId, conn) }
        val drainJob = scope.launch { drainLoop(connectionId, conn) }
        rosterJob.join()
        drainJob.join()
    }

    private suspend fun ensureSupported(connectionId: String, conn: ConnectionLive): Boolean {
        val ready = withTimeoutOrNull(30_000) {
            conn.gateway.state.first { it is SocketState.Ready }
        }
        if (ready == null) {
            manager.markRelayUnsupported(connectionId)
            return false
        }
        return try {
            conn.gateway.request(Catalog.METHOD_BOT_RELAY_ROSTER_SYNC, buildJsonObject { put("agents", JsonArray(emptyList())) }, 30_000)
            true
        } catch (e: RpcException) {
            if (e.isMethodNotFound()) {
                manager.markRelayUnsupported(connectionId)
                false
            } else true // supported but errored once — keep looping
        }
    }

    private suspend fun rosterLoop(connectionId: String, conn: ConnectionLive) {
        while (true) {
            runCatching { rosterSyncOnce(connectionId, conn) }
                .onFailure { log.warning("roster sync failed conn=$connectionId: ${it.message}") }
            delay(Catalog.RELAY_ROSTER_LOOP_MS)
        }
    }

    private suspend fun rosterSyncOnce(connectionId: String, conn: ConnectionLive) {
        // Refresh this connection's own agents, then push the union of the OTHERS to it.
        val mine = fetchAgents(connectionId, conn)
        agentCache[connectionId] = mine
        val others = agentCache.filterKeys { it != connectionId }.values.flatten()
        conn.gateway.request(
            Catalog.METHOD_BOT_RELAY_ROSTER_SYNC,
            buildJsonObject { put("agents", JsonArray(others.map { it.toParams() })) },
            60_000,
        )
    }

    private suspend fun fetchAgents(connectionId: String, conn: ConnectionLive): List<RelayAgent> {
        val result = conn.gateway.request(
            Catalog.METHOD_PROFILES_LIST,
            buildJsonObject { put("include_sessions", false) },
            60_000,
        )
        return (result["profiles"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(key: String): String? =
                (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val profile = str("name") ?: return@mapNotNull null
            val displayName = str("display_name") ?: profile
            RelayAgent(
                profile = profile,
                handle = profile.lowercase().replace(' ', '-'),
                connectionId = connectionId,
                connectionLabel = conn.record.label,
                title = displayName,
                description = str("description").orEmpty(),
            )
        }.orEmpty()
    }

    private suspend fun drainLoop(connectionId: String, conn: ConnectionLive) {
        while (true) {
            runCatching { drainOnce(connectionId, conn) }
                .onFailure { log.warning("drain failed conn=$connectionId: ${it.message}") }
            // 30 s cadence, but an outbox.pending push wakes us early (debounced 500 ms floor).
            withTimeoutOrNull(Catalog.RELAY_DRAIN_LOOP_MS) {
                conn.gateway.events.first { it.type == Catalog.EVENT_BOT_RELAY_OUTBOX_PENDING }
                delay(500)
            }
        }
    }

    private suspend fun drainOnce(connectionId: String, conn: ConnectionLive) {
        val result = conn.gateway.request(
            Catalog.METHOD_BOT_RELAY_OUTBOX_DRAIN,
            JsonObject(emptyMap()),
            60_000,
        )
        val envelopes = (result["envelopes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        for (envelope in envelopes) {
            deliverEnvelope(connectionId, conn, envelope)
        }
    }

    private suspend fun deliverEnvelope(senderId: String, sender: ConnectionLive, envelope: JsonObject) {
        fun str(key: String): String? =
            (envelope[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val envelopeId = str("id") ?: return
        val message = str("message").orEmpty()
        val targetConnection = str("target_connection").orEmpty()
        val targetProfile = str("target_profile").orEmpty()
        val target = manager.live.value[targetConnection]
        val replyParams = if (target == null) {
            buildJsonObject {
                put("id", envelopeId)
                put("reason", "target connection '$targetConnection' is not connected")
            }
        } else {
            try {
                val delivered = target.gateway.request(
                    Catalog.METHOD_BOT_RELAY_DELIVER,
                    buildJsonObject { put("profile", targetProfile); put("message", message) },
                    Catalog.RELAY_DELIVER_TIMEOUT_MS,
                )
                buildJsonObject {
                    put("id", envelopeId)
                    put("reply", (delivered["reply"] as? JsonPrimitive)?.content ?: "")
                }
            } catch (e: RpcException) {
                buildJsonObject {
                    put("id", envelopeId)
                    put("reason", "deliver failed (${e.code}): ${e.message}")
                }
            } catch (e: Exception) {
                buildJsonObject {
                    put("id", envelopeId)
                    put("reason", "deliver failed: ${e.message}")
                }
            }
        }
        runCatching {
            sender.gateway.request(Catalog.METHOD_BOT_RELAY_REPLY, replyParams, 60_000)
        }.onFailure { log.warning("reply failed envelope=$envelopeId: ${it.message}") }
    }
}
