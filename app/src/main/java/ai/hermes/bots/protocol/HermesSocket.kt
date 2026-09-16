package ai.hermes.bots.protocol

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

sealed interface SocketState {
    data object Idle : SocketState
    data object Connecting : SocketState
    data class Ready(val replayEpoch: String) : SocketState
    data class Disconnected(val detail: String, val closeCode: Int?) : SocketState
}

/**
 * One reconnecting WebSocket to a hermes gateway (PROTOCOL.md §2–§3).
 * First frame after accept MUST be gateway.ready (epoch stored on Ready). Client-driven
 * heartbeat: gateway.ping every heartbeatIntervalMs; no ack within heartbeatTimeoutMs forces
 * reconnect. Backoff doubles from backoffMinMs to backoffMaxMs. No Origin header; Host intact.
 */
class HermesSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val urlProvider: suspend () -> String,
    private val heartbeatIntervalMs: Long = Catalog.HEARTBEAT_INTERVAL_MS,
    private val heartbeatTimeoutMs: Long = Catalog.HEARTBEAT_TIMEOUT_MS,
    private val backoffMinMs: Long = Catalog.BACKOFF_MIN_MS,
    private val backoffMaxMs: Long = Catalog.BACKOFF_MAX_MS,
) {
    private val log = Logger.getLogger("HermesSocket")

    private val _state = MutableStateFlow<SocketState>(SocketState.Idle)
    val state: StateFlow<SocketState> = _state

    private val _frames = MutableSharedFlow<RpcFrame>(extraBufferCapacity = 1024)
    val frames: SharedFlow<RpcFrame> = _frames

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectScheduled = false
    private var backoffMs = backoffMinMs
    private var firstFrameSeen = false
    private var lastPongAt = 0L
    private val stopped = AtomicBoolean(false)

    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        log.warning("socket coroutine error: $t")
    }

    fun start() {
        stopped.set(false)
        synchronized(lock) {
            if (_state.value != SocketState.Idle) return
            backoffMs = backoffMinMs
        }
        connect()
    }

    /**
     * Production teardown. Identical to [stop], but `ws.cancel()` runs on Dispatchers.IO:
     * cancel walks OkHttp's TaskQueue shutdown (a global lock) and must never execute on
     * Main (QA S1 — the ANR trace showed Main's IME dispatch contending on that lock).
     * Suspend-context call sites MUST prefer this over [stop].
     */
    suspend fun stopAsync() {
        stopped.set(true)
        val ws = synchronized(lock) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectScheduled = false
            heartbeatJob?.cancel()
            heartbeatJob = null
            val old = webSocket
            webSocket = null
            old
        }
        withContext(Dispatchers.IO) { ws?.cancel() }
        _state.value = SocketState.Idle
    }

    fun stop() {
        stopped.set(true)
        val ws = synchronized(lock) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectScheduled = false
            heartbeatJob?.cancel()
            heartbeatJob = null
            val old = webSocket
            webSocket = null
            old
        }
        ws?.cancel()
        _state.value = SocketState.Idle
    }

    fun send(text: String): Boolean {
        val ws = synchronized(lock) { webSocket } ?: return false
        return ws.send(text)
    }

    private fun connect() {
        _state.value = SocketState.Connecting
        firstFrameSeen = false
        synchronized(lock) { lastPongAt = 0 }
        scope.launch(exceptionHandler) {
            val url = try {
                urlProvider()
            } catch (e: Exception) {
                failSocket("url provider: ${e.message}")
                return@launch
            }
            val request = Request.Builder().url(url).build()
            synchronized(lock) { webSocket = client.newWebSocket(request, listener) }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                backoffMs = backoffMinMs
                lastPongAt = System.currentTimeMillis()
            }
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                JsonRpc.decode(text)
            } catch (e: Exception) {
                log.warning("dropping malformed frame: ${e.message}")
                return
            }
            if (frame is RpcResponse && frame.id == RpcId.Str("h1")) {
                synchronized(lock) { lastPongAt = System.currentTimeMillis() }
            }
            if (!firstFrameSeen) {
                val push = frame as? RpcPush
                val isReady = push != null &&
                    push.method == Catalog.METHOD_EVENT &&
                    (push.params["type"] as? JsonPrimitive)?.content == Catalog.EVENT_GATEWAY_READY
                if (!isReady) {
                    failSocket("first frame was not gateway.ready")
                    return
                }
                val payload = push!!.params["payload"] as? JsonObject
                val epoch = (payload?.get("replay_epoch") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content.orEmpty()
                firstFrameSeen = true
                _state.value = SocketState.Ready(epoch)
                return
            }
            _frames.tryEmit(frame)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Peer-initiated graceful close (e.g. gateway restart): complete the handshake
            // so onClosed fires and the reconnect loop engages.
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failSocket("ws failure: ${t.message ?: t.javaClass.simpleName}", response?.code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stopped.get()) return
            failSocket("closed $code $reason".trim(), code)
        }
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(exceptionHandler) {
            while (true) {
                delay(heartbeatIntervalMs)
                val now = System.currentTimeMillis()
                val ackedAt = synchronized(lock) { lastPongAt }
                if (ackedAt == 0L || now - ackedAt > heartbeatTimeoutMs) {
                    failSocket("heartbeat timeout (${heartbeatTimeoutMs}ms without gateway.ping ack)")
                    return@launch
                }
                if (!ws.send("""{"jsonrpc":"2.0","id":"h1","method":"gateway.ping"}""")) {
                    failSocket("heartbeat send failed")
                    return@launch
                }
            }
        }
    }

    private fun failSocket(detail: String, closeCode: Int? = null) {
        heartbeatJob?.cancel()
        val ws = synchronized(lock) {
            val old = webSocket
            webSocket = null
            old
        }
        ws?.cancel()
        if (stopped.get()) return
        // First failure of a generation wins the detail; cancel-echoes must not overwrite it.
        if (_state.value is SocketState.Ready || _state.value is SocketState.Connecting) {
            _state.value = SocketState.Disconnected(detail, closeCode)
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delayMs = synchronized(lock) {
            if (reconnectScheduled || stopped.get()) return
            reconnectScheduled = true
            // Delay with the CURRENT backoff first (1 s floor per PROTOCOL.md §3), then double.
            val d = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(backoffMaxMs)
            d
        }
        reconnectJob = scope.launch(exceptionHandler) {
            delay(delayMs)
            synchronized(lock) { reconnectScheduled = false }
            if (!stopped.get()) connect()
        }
    }
}
