package ai.hermes.bots.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/** A server-pushed or replayed event (PROTOCOL.md §3–§4). */
data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val seq: Long?,
    val payload: JsonObject,
)

/** Result of session.events.since (PROTOCOL.md §3). */
data class CatchUp(
    val events: List<GatewayEvent>,
    val latestSeq: Long,
    val truncated: Boolean,
    val epoch: String?,
)

class GatewayNotReadyException(val socketState: SocketState) :
    Exception("gateway not ready: $socketState")

/**
 * Typed request/response + event flow over a HermesSocket (PROTOCOL.md §4).
 * Responses are matched by id, never by order (long-running handlers may respond late).
 * Per-session seq watermarks reset when the Ready replay_epoch changes.
 */
class HermesGateway(
    private val socket: HermesSocket,
    private val scope: CoroutineScope,
) {
    private val log = Logger.getLogger("HermesGateway")

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<GatewayEvent> = _events

    /** Per-session highest seq seen from live pushes; reset on replay_epoch change. */
    private val _watermarks = MutableStateFlow<Map<String, Long>>(emptyMap())
    val watermarks: StateFlow<Map<String, Long>> = _watermarks

    /** Incremented each time a new Ready epoch differs from the previous one. */
    private val _epochGeneration = MutableStateFlow(0)
    val epochGeneration: StateFlow<Int> = _epochGeneration

    val state: StateFlow<SocketState> get() = socket.state
    val replayEpoch: String? get() = (socket.state.value as? SocketState.Ready)?.replayEpoch

    private var collectorJob: Job? = null
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        log.warning("gateway error: $t")
    }

    /** Begin consuming socket frames. Call once after construction. */
    fun start() {
        if (collectorJob != null) return
        collectorJob = scope.launch(exceptionHandler) {
            launch { observeEpoch() }
            launch { observeDisconnects() }
            socket.frames.collect { frame -> onFrame(frame) }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /** Typed JSON-RPC request; returns the result object. Throws RpcException on error. */
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long = 60_000): JsonObject {
        val st = socket.state.value
        if (st !is SocketState.Ready) throw GatewayNotReadyException(st)
        val id = RpcId.Num(nextId.getAndIncrement())
        val key = idKey(id)
        val deferred = CompletableDeferred<RpcResponse>()
        pending[key] = deferred
        try {
            // D2: RealWebSocket.send schedules a writer task on OkHttp's shared TaskRunner
            // (global lock). Senders here run on caller contexts (ViewModels = Main), so the
            // send is confined to IO — the ANR trace showed Main's IME dispatch contending
            // with TaskRunner/TaskQueue.shutdown() on that lock.
            val sent = withContext(Dispatchers.IO) { socket.send(JsonRpc.encodeRequest(id, method, params)) }
            if (!sent) throw GatewayNotReadyException(socket.state.value)
            val response = withTimeout(timeoutMs) { deferred.await() }
            response.error?.let { throw RpcException(it) }
            return response.result as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            pending.remove(key)
        }
    }

    /** session.events.since — bare replay params come back inside result.events (PROTOCOL.md §3). */
    suspend fun since(sessionId: String, lastSeen: Long, timeoutMs: Long = 60_000): CatchUp {
        val result = request(
            Catalog.METHOD_SESSION_EVENTS_SINCE,
            buildJsonObject {
                put("session_id", sessionId)
                put("last_seen", lastSeen)
            },
            timeoutMs,
        )
        val events = (result["events"] as? JsonArray)
            ?.mapNotNull { el -> (el as? JsonObject)?.let { parseEvent(it) } }
            .orEmpty()
        return CatchUp(
            events = events,
            latestSeq = (result["latest_seq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            truncated = (result["truncated"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            epoch = (result["epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }

    private suspend fun onFrame(frame: RpcFrame) {
        when (frame) {
            is RpcResponse -> pending.remove(idKey(frame.id))?.complete(frame)
            is RpcPush -> {
                if (frame.method == Catalog.METHOD_EVENT) {
                    parseEvent(frame.params)?.let { ev ->
                        trackWatermark(ev)
                        _events.emit(ev)
                    }
                }
            }
            is RpcServerRequest -> Unit // server-initiated request; not used by v1
        }
    }

    private suspend fun observeEpoch() {
        var lastEpoch: String? = null
        socket.state.collect { st ->
            if (st is SocketState.Ready) {
                if (lastEpoch != null && st.replayEpoch != lastEpoch) {
                    _watermarks.value = emptyMap()
                    _epochGeneration.value += 1
                }
                lastEpoch = st.replayEpoch
            }
        }
    }

    private suspend fun observeDisconnects() {
        socket.state.collect { st ->
            if (st is SocketState.Disconnected || st is SocketState.Idle) {
                pending.values.forEach { it.completeExceptionally(GatewayNotReadyException(st)) }
                pending.clear()
            }
        }
    }

    private fun parseEvent(params: JsonObject): GatewayEvent? {
        val type = (params["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val sessionId = (params["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val seq = (params["seq"] as? JsonPrimitive)?.content?.toLongOrNull()
        val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
        return GatewayEvent(type, sessionId, seq, payload)
    }

    private fun trackWatermark(ev: GatewayEvent) {
        val sid = ev.sessionId ?: return
        val seq = ev.seq ?: return
        _watermarks.update { cur ->
            val best = cur[sid]
            if (best == null || seq > best) cur + (sid to seq) else cur
        }
    }

    private fun idKey(id: RpcId): String = when (id) {
        is RpcId.Num -> "n:${id.value}"
        is RpcId.Str -> "s:${id.value}"
    }
}
