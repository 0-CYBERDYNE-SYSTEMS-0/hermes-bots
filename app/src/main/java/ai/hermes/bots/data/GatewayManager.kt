package ai.hermes.bots.data

import ai.hermes.bots.protocol.Auth
import ai.hermes.bots.protocol.FleetProbe
import ai.hermes.bots.protocol.FleetProbeResult
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.GatewayProbe
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.HermesSocket
import ai.hermes.bots.protocol.SocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** One live connection: its record, socket state, and the typed gateway on top. */
data class ConnectionLive(
    val record: ConnectionRecord,
    val socket: HermesSocket,
    val gateway: HermesGateway,
)

class GatewayManager(
    private val repo: ConnectionRepository,
    private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder().cookieJar(Auth.COOKIE_JAR).build()

    private val _live = MutableStateFlow<Map<String, ConnectionLive>>(emptyMap())
    val live: StateFlow<Map<String, ConnectionLive>> = _live

    private val _socketStates = MutableStateFlow<Map<String, SocketState>>(emptyMap())
    val socketStates: StateFlow<Map<String, SocketState>> = _socketStates

    /**
     * Per-connection relay observability (B5): {Unknown, Supported, Unsupported} + last
     * successful drain. Never latches — RelayEngine re-probes on reconnect and flips it back.
     */
    private val relayBoard = RelayStatusBoard()
    val relayStatus: StateFlow<Map<String, RelayStatus>> = relayBoard.states

    /** Called by RelayEngine when the roster.sync probe succeeds. */
    fun markRelaySupported(connectionId: String) {
        relayBoard.markSupported(connectionId)
    }

    /** Called by RelayEngine on -32601; the engine re-probes on reconnect, clearing this. */
    fun markRelayUnsupported(connectionId: String) {
        relayBoard.markUnsupported(connectionId)
    }

    /** Called by RelayEngine after every successful bot_relay.outbox.drain. */
    fun markRelayDrained(connectionId: String, atMs: Long = System.currentTimeMillis()) {
        relayBoard.markDrained(connectionId, atMs)
    }

    private val stateJobs = mutableMapOf<String, Job>()
    private var syncJob: Job? = null

    /** Observe saved connections and keep live sockets matched to them. */
    fun start() {
        if (syncJob != null) return
        syncJob = scope.launch {
            repo.connections.collect { records -> reconcile(records) }
        }
    }

    suspend fun probe(record: ConnectionRecord): GatewayProbe =
        Auth.probe(client, record.baseUrl)

    /** Full verification probe (B6): health + credentials + one-shot WS + capability bits. */
    suspend fun verifyFleet(record: ConnectionRecord): FleetProbeResult =
        FleetProbe.run(client, record.baseUrl, record.auth)

    private suspend fun reconcile(records: List<ConnectionRecord>) {
        val wanted = records.associateBy { it.id }
        (_live.value.keys - wanted.keys).forEach { stopOne(it) }
        wanted.values.forEach { rec ->
            val current = _live.value[rec.id]
            val stale = current != null && current.record != rec
            if (current == null || stale) {
                stopOne(rec.id)
                startOne(rec)
            }
        }
    }

    private fun startOne(rec: ConnectionRecord) {
        val socket = HermesSocket(
            client = client,
            scope = scope,
            urlProvider = { wsUrlFor(rec) },
        )
        val gateway = HermesGateway(socket, scope)
        gateway.start()
        socket.start()
        _live.update { it + (rec.id to ConnectionLive(rec, socket, gateway)) }
        _socketStates.update { it + (rec.id to socket.state.value) }
        relayBoard.reset(rec.id)
        stateJobs[rec.id]?.cancel()
        stateJobs[rec.id] = scope.launch {
            socket.state.collect { st ->
                _socketStates.update { m -> m + (rec.id to st) }
            }
        }
    }

    private fun stopOne(id: String) {
        stateJobs.remove(id)?.cancel()
        _live.value[id]?.let { conn ->
            conn.gateway.stop()
            conn.socket.stop()
        }
        _live.update { it - id }
        _socketStates.update { it - id }
        relayBoard.remove(id)
    }

    private suspend fun wsUrlFor(rec: ConnectionRecord): String = when (val auth = rec.auth) {
        is GatewayAuth.TokenAuth -> Auth.wsUrlWithAuth(rec.baseUrl, auth.token)
        is GatewayAuth.BasicAuth -> {
            val ticket = Auth.mintTicket(client, rec.baseUrl, auth)
            Auth.wsUrlWithTicket(rec.baseUrl, ticket)
        }
    }
}
