package ai.hermes.bots.ui.chat

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ApprovalCard
import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.CanonicalChat
import ai.hermes.bots.data.ChatItem
import ai.hermes.bots.data.ChatMessagesParser
import ai.hermes.bots.data.ChatUiState
import ai.hermes.bots.data.ItemKind
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.GatewayEvent
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.RpcException
import ai.hermes.bots.protocol.SocketState
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatViewModel(
    app: Application,
    private val connectionId: String,
    private val botName: String,
) : AndroidViewModel(app) {

    private val graph = (app as HermesBotsApp).graph
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui
    val avatars = graph.roster.avatars

    private var gateway: HermesGateway? = null
    private var runtimeSessionId: String? = null
    private var lastSeq = 0L
    private var storedEpoch: String? = null
    private var itemCounter = 0
    private var eventJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        try {
            val row = withTimeout(15_000) {
                graph.roster.roster
                    .first { rows -> rows.any { it.bot.connectionId == connectionId && it.bot.name == botName } }
                    .first { it.bot.connectionId == connectionId && it.bot.name == botName }
                    .bot
            }
            val conn = graph.gateways.live.value[connectionId]
                ?: throw IllegalStateException("gateway connection is not live")
            val gw = conn.gateway
            gateway = gw
            val canonicalId = row.canonicalSessionId
            var adopted = false
            if (canonicalId != null) {
                try {
                    adopt(gw.request(Catalog.METHOD_SESSION_RESUME, buildJsonObject { put("session_id", canonicalId) }, 120_000))
                    adopted = true
                } catch (_: Exception) {
                    // canonical session gone — fall through to create
                }
            }
            if (!adopted) {
                adopt(gw.request(Catalog.METHOD_SESSION_CREATE, CanonicalChat.createParams(botName), 120_000))
            }
            _ui.update { it.copy(botModel = row.model) }
            startCollectors()
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = e.message ?: "failed to open chat") }
        }
    }

    private fun adopt(result: JsonObject) {
        runtimeSessionId = (result["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val card = CanonicalChat.parseCard(
            Catalog.EVENT_APPROVAL_REQUEST,
            result["pending_approval"] as? JsonObject,
        ) ?: CanonicalChat.parseCard(
            Catalog.EVENT_CLARIFY_REQUEST,
            result["pending_clarify"] as? JsonObject,
        )
        val running = boolField(result, "running") == true || boolField(result, "inflight") == true
        _ui.update {
            it.copy(
                loading = false,
                error = null,
                items = ChatMessagesParser.parse(result["messages"] as? JsonArray),
                approval = card,
                streaming = running,
            )
        }
    }

    private fun startCollectors() {
        val gw = gateway ?: return
        val sid = runtimeSessionId ?: return
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            gw.events.collect { ev -> if (ev.sessionId == sid) onEvent(ev) }
        }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            storedEpoch = (gw.state.value as? SocketState.Ready)?.replayEpoch
            // The socket walks Disconnected → Connecting → Ready, so a simple previous-state
            // compare misses the edge; latch "dropped since last Ready" instead.
            var droppedSinceReady = false
            gw.state.collect { st ->
                if (st is SocketState.Disconnected) {
                    droppedSinceReady = true
                    return@collect
                }
                if (st is SocketState.Ready) {
                    val epochChanged = storedEpoch != null && st.replayEpoch != storedEpoch
                    val wasDropped = droppedSinceReady
                    droppedSinceReady = false
                    if (storedEpoch == null) {
                        storedEpoch = st.replayEpoch
                    } else if (wasDropped) {
                        storedEpoch = st.replayEpoch
                        if (epochChanged) reload() else catchUp()
                    }
                }
            }
        }
    }

    private suspend fun catchUp() {
        val gw = gateway ?: return
        val sid = runtimeSessionId ?: return
        try {
            val result = gw.since(sid, lastSeq)
            if (result.truncated) {
                reload()
                return
            }
            result.events.forEach { onEvent(it) }
        } catch (_: Exception) {
            // best-effort; next sessions.changed/5s roster still functions
        }
    }

    private suspend fun reload() {
        val gw = gateway ?: return
        val sid = runtimeSessionId ?: return
        try {
            adopt(gw.request(Catalog.METHOD_SESSION_RESUME, buildJsonObject { put("session_id", sid) }, 120_000))
        } catch (e: Exception) {
            _ui.update { it.copy(error = "reload failed: ${e.message}") }
        }
    }

    fun onEvent(ev: GatewayEvent) {
        ev.seq?.let { if (it > lastSeq) lastSeq = it }
        fun str(key: String): String? =
            (ev.payload[key] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        when (ev.type) {
            Catalog.EVENT_MESSAGE_START -> _ui.update { st ->
                st.copy(
                    items = finalizeStreaming(st.items) + ChatItem("a-${++itemCounter}", ItemKind.ASSISTANT, "", streaming = true),
                    streaming = true,
                )
            }
            Catalog.EVENT_MESSAGE_DELTA -> _ui.update { st ->
                st.copy(items = appendDelta(st.items, str("text") ?: ""))
            }
            Catalog.EVENT_MESSAGE_INTERIM -> _ui.update { st ->
                st.copy(items = setStreamingText(st.items, str("text") ?: ""))
            }
            Catalog.EVENT_MESSAGE_COMPLETE -> _ui.update { st ->
                var items = setStreamingText(st.items, str("text") ?: "", finalize = true)
                val errText = str("error")
                val status = str("status")
                if (errText != null || status == "error") {
                    items = items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, errText ?: "turn error")
                }
                st.copy(items = items, streaming = false, statusText = null)
            }
            Catalog.EVENT_TOOL_START -> _ui.update { st ->
                val toolId = (ev.payload["tool_id"] as? JsonPrimitive)?.content ?: "n${itemCounter + 1}"
                st.copy(
                    items = st.items + ChatItem(
                        id = "tool-$toolId",
                        kind = ItemKind.TOOL,
                        text = str("args_text") ?: "",
                        streaming = true,
                        toolName = str("name") ?: "tool",
                    ),
                )
            }
            Catalog.EVENT_TOOL_COMPLETE -> _ui.update { st ->
                val toolId = (ev.payload["tool_id"] as? JsonPrimitive)?.content
                val idx = toolId?.let { id -> st.items.indexOfFirst { it.id == "tool-$id" } } ?: -1
                val items = if (idx >= 0) {
                    st.items.toMutableList().also {
                        it[idx] = it[idx].copy(
                            streaming = false,
                            summary = str("summary"),
                            durationS = (ev.payload["duration_s"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                        )
                    }
                } else {
                    st.items
                }
                st.copy(items = items)
            }
            Catalog.EVENT_STATUS_UPDATE -> _ui.update { it.copy(statusText = str("text")) }
            Catalog.EVENT_APPROVAL_REQUEST,
            Catalog.EVENT_CLARIFY_REQUEST,
            Catalog.EVENT_SUDO_REQUEST,
            Catalog.EVENT_SECRET_REQUEST,
            -> _ui.update {
                it.copy(
                    approval = CanonicalChat.parseCard(ev.type, ev.payload),
                    approvalResolved = null,
                    approvalExpired = false,
                )
            }
            Catalog.EVENT_SESSION_TITLE -> _ui.update { it.copy(sessionTitle = str("title")) }
            Catalog.EVENT_ERROR -> _ui.update { st ->
                st.copy(
                    items = st.items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, str("message") ?: "error"),
                    streaming = false,
                )
            }
            else -> {
                if (ev.type.endsWith(".expire")) {
                    val rid = str("request_id")
                    _ui.update { st ->
                        when {
                            st.approval?.requestId != null && st.approval?.requestId == rid ->
                                st.copy(approval = null, approvalExpired = true, approvalResolved = null)
                            else -> st
                        }
                    }
                }
            }
        }
    }

    fun send(rawText: String) {
        val sid = runtimeSessionId ?: return
        val text = rawText.trim()
        if (text.isEmpty()) return
        if (CanonicalChat.isOpenCommand(text)) {
            viewModelScope.launch {
                try {
                    gateway?.request(Catalog.METHOD_SESSION_COMPRESS, CanonicalChat.compressParams(sid))
                    reload()
                } catch (e: Exception) {
                    _ui.update { it.copy(error = "compress failed: ${e.message}") }
                }
            }
            return
        }
        viewModelScope.launch {
            _ui.update { st ->
                st.copy(items = finalizeStreaming(st.items) + ChatItem("u-${++itemCounter}", ItemKind.USER, text))
            }
            try {
                gateway?.request(Catalog.METHOD_PROMPT_SUBMIT, CanonicalChat.submitParams(sid, text), 30_000)
                _ui.update { it.copy(streaming = true, error = null) }
            } catch (e: RpcException) {
                _ui.update { st ->
                    st.copy(
                        streaming = false,
                        items = st.items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, "submit failed (${e.code}): ${e.message}"),
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(streaming = false, error = "submit failed: ${e.message}") }
            }
        }
    }

    fun respond(choice: String) {
        val sid = runtimeSessionId ?: return
        val card = _ui.value.approval ?: return
        viewModelScope.launch {
            try {
                if (card.kind == Catalog.EVENT_CLARIFY_REQUEST) {
                    gateway?.request(
                        Catalog.METHOD_CLARIFY_RESPOND,
                        buildJsonObject {
                            put("session_id", sid)
                            put("request_id", card.requestId)
                            put("answer", choice)
                        },
                    )
                } else {
                    gateway?.request(
                        Catalog.METHOD_APPROVAL_RESPOND,
                        buildJsonObject {
                            put("session_id", sid)
                            put("request_id", card.requestId)
                            put("choice", choice)
                        },
                    )
                }
                _ui.update { it.copy(approval = null, approvalResolved = choice) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "respond failed: ${e.message}") }
            }
        }
    }

    fun interrupt() {
        val sid = runtimeSessionId ?: return
        viewModelScope.launch {
            runCatching {
                gateway?.request(Catalog.METHOD_SESSION_INTERRUPT, buildJsonObject { put("session_id", sid) })
            }
            _ui.update { it.copy(streaming = false, statusText = null) }
        }
    }

    fun steer(text: String) {
        val sid = runtimeSessionId ?: return
        viewModelScope.launch {
            runCatching {
                gateway?.request(
                    Catalog.METHOD_SESSION_STEER,
                    buildJsonObject { put("session_id", sid); put("text", text) },
                )
            }
        }
    }

    private fun finalizeStreaming(items: List<ChatItem>): List<ChatItem> =
        items.map { if (it.streaming) it.copy(streaming = false) else it }

    private fun appendDelta(items: List<ChatItem>, delta: String): List<ChatItem> {
        if (delta.isEmpty()) return items
        val idx = items.indexOfLast { it.streaming && it.kind == ItemKind.ASSISTANT }
        if (idx < 0) return items + ChatItem("a-${++itemCounter}", ItemKind.ASSISTANT, delta, streaming = true)
        val cur = items[idx]
        return items.toMutableList().also { it[idx] = cur.copy(text = cur.text + delta) }
    }

    private fun setStreamingText(items: List<ChatItem>, value: String, finalize: Boolean = false): List<ChatItem> {
        val idx = items.indexOfLast { it.streaming && it.kind == ItemKind.ASSISTANT }
        if (idx < 0) {
            return if (finalize && value.isEmpty()) items
            else items + ChatItem("a-${++itemCounter}", ItemKind.ASSISTANT, value, streaming = !finalize)
        }
        val cur = items[idx]
        return items.toMutableList().also { it[idx] = cur.copy(text = value, streaming = !finalize) }
    }

    private fun boolField(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    override fun onCleared() {
        eventJob?.cancel()
        reconnectJob?.cancel()
        super.onCleared()
    }
}
