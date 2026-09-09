package ai.hermes.bots.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Result of the full verification probe (FLEET-CONNECT-SPEC B6). `verified` means health +
 * credentials + one-shot WS `gateway.ready` handshake all succeeded; the capability bits are
 * best-effort and null when that leg couldn't be answered (shown as "?" in the UI).
 */
data class FleetProbeResult(
    val reachable: Boolean = false,
    val signedIn: Boolean = false,
    val verified: Boolean = false,
    val serverVersion: String? = null,
    val replayEpoch: String? = null,
    val groupsSupported: Boolean? = null,
    val relaySupported: Boolean? = null,
    val failure: String? = null,
)

/**
 * Full verification chain against one gateway (B6), replacing the REST-only Test:
 * /api/status (health + auth detection) → credentials (token query param for open gateways,
 * cookie login + ws-ticket for gated — Auth.kt handles both, reused verbatim) → one-shot WS
 * whose first frame must be `gateway.ready` (epoch captured) → capability bits:
 * relay = `bot_relay.roster.sync` accepted (-32601 ⇒ unsupported, same probe RelayEngine
 * uses), groups = `groups.capabilities` (-32601 ⇒ unsupported, same signal GroupRepository
 * gates on). Runs on its own short-lived socket, safe beside an already-open main socket.
 * Every step is time-boxed so the button can't hang; failures report partial results with
 * humane copy (no protocol slugs / HTTP codes).
 */
object FleetProbe {

    data class Budgets(
        val statusMs: Long = 4_000,
        val ticketMs: Long = 5_000,
        val wsMs: Long = 6_000,
        val capsMs: Long = 4_000,
    )

    suspend fun run(
        client: OkHttpClient,
        baseUrl: String,
        auth: GatewayAuth,
        budgets: Budgets = Budgets(),
    ): FleetProbeResult {
        // 1. Health + auth-mode detection (PROTOCOL.md §7 — /api/status is public).
        val status = withTimeoutOrNull(budgets.statusMs) { Auth.probe(client, baseUrl) }
            ?: return FleetProbeResult(failure = "Couldn't reach the gateway — check the address.")
        if (!status.reachable || status.error != null) {
            return FleetProbeResult(
                reachable = status.reachable,
                serverVersion = status.version,
                failure = statusFailure(status),
            )
        }

        // 2. Credentials → authenticated WS URL. Token mode appends ?token=; gated mode
        //    cookie-logins and mints a single-use ticket (mintTicket relogs-in on 401).
        val wsUrl = try {
            withTimeoutOrNull(budgets.ticketMs) {
                when (auth) {
                    is GatewayAuth.TokenAuth -> Auth.wsUrlWithAuth(baseUrl, auth.token)
                    is GatewayAuth.BasicAuth -> Auth.wsUrlWithTicket(baseUrl, Auth.mintTicket(client, baseUrl, auth))
                }
            } ?: return FleetProbeResult(
                reachable = true,
                serverVersion = status.version,
                failure = "Signing in took too long — check the credentials and try again.",
            )
        } catch (e: ProtocolException) {
            return FleetProbeResult(
                reachable = true,
                serverVersion = status.version,
                failure = when (auth) {
                    is GatewayAuth.BasicAuth -> "Couldn't sign in — check the username and password."
                    is GatewayAuth.TokenAuth -> "Couldn't sign in — check the session token."
                },
            )
        }

        // 3. One-shot WS handshake on a throwaway socket (two sockets on one gateway is fine).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = HermesSocket(client, scope, urlProvider = { wsUrl })
        val gateway = HermesGateway(socket, scope)
        try {
            gateway.start()
            socket.start()
            val ready = withTimeoutOrNull(budgets.wsMs) {
                socket.state.first { it is SocketState.Ready }
            } as? SocketState.Ready
            if (ready == null) {
                val disconnected = socket.state.value as? SocketState.Disconnected
                return FleetProbeResult(
                    reachable = true,
                    serverVersion = status.version,
                    failure = when (disconnected?.closeCode) {
                        Catalog.WS_CLOSE_BAD_CREDENTIAL ->
                            if (auth is GatewayAuth.BasicAuth) {
                                "Couldn't sign in — check the username and password."
                            } else {
                                "The gateway rejected the session token."
                            }
                        else -> "Reached the gateway, but the chat channel didn't answer."
                    },
                )
            }

            // 4. Capability bits, in parallel, each time-boxed. Null (⇒ "?") only when the
            //    gateway went quiet mid-probe — a -32601 answer is a real ✗, not a failure.
            val relayBit = scope.async {
                capability {
                    gateway.request(
                        Catalog.METHOD_BOT_RELAY_ROSTER_SYNC,
                        buildJsonObject { put("agents", JsonArray(emptyList())) },
                        budgets.capsMs,
                    )
                }
            }
            val groupsBit = scope.async {
                capability {
                    gateway.request(
                        Catalog.METHOD_GROUPS_CAPABILITIES,
                        JsonObject(emptyMap()),
                        budgets.capsMs,
                    )
                }
            }
            val relaySupported = withTimeoutOrNull(budgets.capsMs + 1_000) { relayBit.await() }
            val groupsSupported = withTimeoutOrNull(budgets.capsMs + 1_000) { groupsBit.await() }
            return FleetProbeResult(
                reachable = true,
                signedIn = true,
                verified = true,
                serverVersion = status.version,
                replayEpoch = ready.replayEpoch,
                groupsSupported = groupsSupported,
                relaySupported = relaySupported,
            )
        } finally {
            runCatching {
                gateway.stop()
                socket.stop()
            }
            scope.cancel()
        }
    }

    /** Capability decision: success ⇒ true, method missing ⇒ false, anything else ⇒ unknown. */
    internal fun capabilityFromException(e: Exception?): Boolean? = when {
        e == null -> true
        e is RpcException && e.isMethodNotFound() -> false
        else -> null
    }

    private suspend fun capability(block: suspend () -> JsonObject): Boolean? =
        try {
            block()
            capabilityFromException(null)
        } catch (e: Exception) {
            capabilityFromException(e)
        }

    /** Humane copy for a failed /api/status leg (kept pure for tests; no HTTP codes). */
    internal fun statusFailure(probe: GatewayProbe): String? = when {
        !probe.reachable -> "Couldn't reach the gateway — check the address."
        probe.error != null -> "That address answered, but it doesn't look like a Hermes gateway."
        else -> null
    }
}
