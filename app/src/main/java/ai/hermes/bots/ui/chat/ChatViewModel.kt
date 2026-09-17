package ai.hermes.bots.ui.chat

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AnyChatSendRetry
import ai.hermes.bots.data.CanonicalChat
import ai.hermes.bots.data.ChatItem
import ai.hermes.bots.data.ChatMessagesParser
import ai.hermes.bots.data.ChatStream
import ai.hermes.bots.data.ChatUiState
import ai.hermes.bots.data.ItemKind
import ai.hermes.bots.data.PendingImage
import ai.hermes.bots.data.ToolResult
import ai.hermes.bots.data.TurnWatchdog
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.GatewayEvent
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.RpcException
import ai.hermes.bots.protocol.SocketState
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


/** Attachments are downscaled to this max dimension before JPEG q80 + base64 (server cap is 25 MB). */
private const val ATTACH_MAX_DIMEN = 1280


class ChatViewModel(
    app: Application,
    private val connectionId: String,
    private val botName: String,
) : AndroidViewModel(app) {

    private val graph = (app as HermesBotsApp).graph
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui

    // SV-01: true only after the session successfully opened; reset when open fails.
    private val _sessionOpen = MutableStateFlow(false)

    /** The composer may only send while the session is open and not loading. */
    val canSend: StateFlow<Boolean> = combine(_ui, _sessionOpen) { st, open -> open && !st.loading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Presentation-only receive stamps for time separators (audit A5): item id → wall clock
    // at first appearance. History from session.resume gets "now" — gaps still separate turns.
    private val _itemTimes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val itemTimes: StateFlow<Map<String, Long>> = _itemTimes

    /** Header presence line (audit A2): "Active now" / "Idle · seen 17h" — never the model slug. */
    val presence: StateFlow<String?> = graph.roster.roster
        .map { rows ->
            val entry = rows.firstOrNull { it.bot.connectionId == connectionId && it.bot.name == botName }
            when {
                entry == null -> null
                entry.activeNow -> "Active now"
                entry.bot.lastActiveMs != null -> "Idle · seen ${seenAgo(entry.bot.lastActiveMs)}"
                else -> null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val avatars = graph.roster.avatars

    /** §4.2 rev Bubble Mode: iMessage-style transcript toggle, persisted in settings. */
    val bubbleMode: StateFlow<Boolean> = graph.settings.bubbleMode

    /** Flips the persisted toggle behind the chat overflow "Bubble mode" item. */
    fun toggleBubbleMode() {
        viewModelScope.launch { graph.settings.setBubbleMode(!bubbleMode.value) }
    }

    /** B4: the owning gateway's label when this bot's name collides across gateways. */
    val gatewayLabel: StateFlow<String?> = combine(graph.roster.roster, graph.connections.connections) { rows, conns ->
        val row = rows.firstOrNull { it.bot.connectionId == connectionId && it.bot.name == botName }
            ?: return@combine null
        if (row.bot.name !in ai.hermes.bots.data.BotNameCollisions.compute(rows.map { it.bot })) return@combine null
        conns.firstOrNull { it.id == connectionId }?.label
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var gateway: HermesGateway? = null
    private var runtimeSessionId: String? = null
    private var lastSeq = 0L
    private var storedEpoch: String? = null
    private var itemCounter = 0
    private var eventJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    // TurnWatchdog (incident 2026-09-16): last session-event receive stamp + one-shot latches.
    // Refreshed on EVERY gateway event for this session — server turns legitimately pause
    // for long tool runs, so staleness is event-activity silence, never time-since-start.
    private var lastActivityMs = 0L
    private var stalledTurn = false
    private var quietNoticed = false

    /** Clarify request_ids already skip-answered from send() — fire at most once each. */
    private val clarifySkipped = mutableSetOf<String>()

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch {
            ui.collect { st -> stampNewItems(st.items) }
        }
    }

    private fun stampNewItems(items: List<ChatItem>) {
        val current = _itemTimes.value
        if (items.all { current.containsKey(it.id) }) return
        val now = System.currentTimeMillis()
        val next = current.toMutableMap()
        items.forEach { if (!next.containsKey(it.id)) next[it.id] = now }
        _itemTimes.value = next
    }

    private fun seenAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
        val delta = (now - ms).coerceAtLeast(0)
        return when {
            delta < 60_000L -> "now"
            delta < 3_600_000L -> "${delta / 60_000L}m"
            delta < 86_400_000L -> "${delta / 3_600_000L}h"
            else -> "${delta / 86_400_000L}d"
        }
    }

    /** SV-01: re-open after a failed open — backs the "Try again" affordance in ChatScreen. */
    fun retry() {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        try {
            _ui.update { it.copy(loading = true, error = null) }
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
                    // Server ≥0.21.1 resolves profile-scoped sessions only when the profile is named.
                    adopt(gw.request(
                        Catalog.METHOD_SESSION_RESUME,
                        buildJsonObject { put("session_id", canonicalId); put("profile", botName) },
                        120_000,
                    ))
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
            _sessionOpen.value = false
            _ui.update { it.copy(loading = false, error = e.message ?: "failed to open chat") }
        }
    }

    private fun adopt(result: JsonObject) {
        runtimeSessionId = (result["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        _sessionOpen.value = runtimeSessionId != null
        val card = CanonicalChat.parseCard(
            Catalog.EVENT_APPROVAL_REQUEST,
            result["pending_approval"] as? JsonObject,
        ) ?: CanonicalChat.parseCard(
            Catalog.EVENT_CLARIFY_REQUEST,
            result["pending_clarify"] as? JsonObject,
        )
        val running = boolField(result, "running") == true || boolField(result, "inflight") == true
        lastActivityMs = System.currentTimeMillis()
        stalledTurn = false
        quietNoticed = false
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
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            while (true) {
                delay(TurnWatchdog.CHECK_INTERVAL_MS)
                val st = _ui.value
                when (TurnWatchdog.phase(st.streaming, stalledTurn, lastActivityMs, System.currentTimeMillis())) {
                    TurnWatchdog.Phase.HARD -> declareStall()
                    TurnWatchdog.Phase.SOFT -> if (!quietNoticed) {
                        quietNoticed = true
                        // SOFT is advisory only: the composer and strip stay exactly as they are.
                        _ui.update { running -> running.copy(items = ChatStream.quietNotice(running.items) { "quiet-${++itemCounter}" }) }
                    }
                    TurnWatchdog.Phase.NONE -> {}
                }
            }
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
        } catch (e: RpcException) {
            // Zombie session (live dogfood 2026-09-16): the gateway reaped the ws-attached
            // session while the socket was down — heal instead of leaving every send 4001-ing.
            if (AnyChatSendRetry.isStaleSessionError(e.code, e.message)) healAfterStaleSession()
        } catch (_: Exception) {
            // best-effort; next sessions.changed/5s roster still functions
        }
    }

    private suspend fun reload() {
        val gw = gateway ?: return
        val sid = runtimeSessionId ?: return
        try {
            adopt(gw.request(
                Catalog.METHOD_SESSION_RESUME,
                buildJsonObject { put("session_id", sid); put("profile", botName) },
                120_000,
            ))
        } catch (e: RpcException) {
            if (AnyChatSendRetry.isStaleSessionError(e.code, e.message)) {
                healAfterStaleSession()
            } else {
                // Q9: humane banner, nothing technical appended (the raw cause stays in logs).
                _ui.update { it.copy(error = "Couldn't load the conversation.") }
            }
        } catch (_: Exception) {
            // Q9: humane banner, nothing technical appended (the raw cause stays in logs).
            _ui.update { it.copy(error = "Couldn't load the conversation.") }
        }
    }

    fun onEvent(ev: GatewayEvent) {
        ev.seq?.let { if (it > lastSeq) lastSeq = it }
        // TurnWatchdog: any session-scoped event is proof of life. A late event after a
        // stall drops the stall latch and clears the stale notice; a late message.complete
        // settles the transcript WITHOUT resurrecting streaming (its branch copies
        // streaming = false unconditionally), and only a fresh message.start may re-arm
        // the steer/stop composer.
        lastActivityMs = System.currentTimeMillis()
        if (stalledTurn || quietNoticed) {
            stalledTurn = false
            quietNoticed = false
            _ui.update { st -> st.copy(items = ChatStream.clearStallNotices(st.items)) }
        }
        fun str(key: String): String? =
            (ev.payload[key] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        when (ev.type) {
            Catalog.EVENT_MESSAGE_START -> _ui.update { st ->
                st.copy(
                    items = finalizeStreaming(st.items) + ChatItem("a-${++itemCounter}", ItemKind.ASSISTANT, "", streaming = true),
                    streaming = true,
                    // The banner described the previous attempt; a new turn supersedes it.
                    error = null,
                )
            }
            Catalog.EVENT_MESSAGE_DELTA -> _ui.update { st ->
                st.copy(items = appendDelta(st.items, str("text") ?: ""))
            }
            Catalog.EVENT_MESSAGE_INTERIM -> _ui.update { st ->
                // Q2: seal the interim as its own segment (desktop parity) — never overwrite
                // the streamed anchor, never duplicate already-streamed text.
                st.copy(items = ChatStream.sealInterim(
                    st.items,
                    str("text") ?: "",
                    boolField(ev.payload, "already_streamed") == true,
                ) { "a-${++itemCounter}" })
            }
            Catalog.EVENT_MESSAGE_COMPLETE -> _ui.update { st ->
                // Q2: complete replaces ONLY the newest live anchor; sealed segments survive.
                var items = ChatStream.completeAnchor(st.items, str("text") ?: "") { "a-${++itemCounter}" }
                val errText = str("error")
                val status = str("status")
                if (errText != null || status == "error") {
                    items = items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, errText ?: "turn error")
                }
                // PROTOCOL.md §5.1/§6: message.complete may carry `warning?` — previously
                // dropped on the floor. Rendered as a dismissible verbatim system line.
                val warning = str("warning")
                if (!warning.isNullOrBlank()) {
                    items = items + ChatItem("warn-${++itemCounter}", ItemKind.ERROR, warning)
                }
                st.copy(items = items, streaming = false, statusText = null)
            }
            Catalog.EVENT_TOOL_START -> _ui.update { st ->
                val toolId = (ev.payload["tool_id"] as? JsonPrimitive)?.content ?: "n${itemCounter + 1}"
                st.copy(
                    items = st.items + ChatItem(
                        id = "tool-$toolId",
                        kind = ItemKind.TOOL,
                        // Non-verbose sessions omit args_text — fall back to the command
                        // inside the always-present `args` payload (live QA 2026-09-14).
                        text = str("args_text")
                            ?: ToolResult.commandFromArgs(str("name"), ev.payload["args"])
                            ?: "",
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
                            failed = ToolResult.isFailure(
                                str("name") ?: it[idx].toolName,
                                ev.payload["result"],
                            ),
                            // Q7 follow-up: result_text is verbose-only on the wire; flatten
                            // the always-present `result` so terminal chips can expand.
                            outputText = ToolResult.displayText(ev.payload["result"]),
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
                    // Incident 2026-09-16: the "Working —" strip must never outlive the turn.
                    statusText = null,
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
        val pending = _ui.value.pendingImage
        if (text.isEmpty() && pending == null) return
        if (CanonicalChat.isOpenCommand(text)) {
            viewModelScope.launch {
                try {
                    gateway?.request(Catalog.METHOD_SESSION_COMPRESS, CanonicalChat.compressParams(sid))
                    reload()
                } catch (_: Exception) {
                    _ui.update { it.copy(error = "Couldn't start a fresh chat — try again.") }
                }
            }
            return
        }
        viewModelScope.launch {
            skipPendingClarify(sid)
            val bubble = when {
                pending != null && text.isNotEmpty() -> "$text\n🖼 ${pending.filename}"
                pending != null -> "🖼 ${pending.filename}"
                else -> text
            }
            _ui.update { st ->
                st.copy(items = finalizeStreaming(st.items) + ChatItem("u-${++itemCounter}", ItemKind.USER, bubble))
            }
            try {
                if (pending != null) {
                    gateway?.request(
                        Catalog.METHOD_IMAGE_ATTACH_BYTES,
                        buildJsonObject {
                            put("session_id", sid)
                            put("content_base64", pending.base64)
                            put("filename", pending.filename)
                        },
                        60_000,
                    )
                }
                // Image-only sends ride the server's own attachment turn-text convention.
                val submitText = text.ifBlank { "[User attached image: ${pending?.filename}]" }
                gateway?.request(Catalog.METHOD_PROMPT_SUBMIT, CanonicalChat.submitParams(sid, submitText), 30_000)
                lastActivityMs = System.currentTimeMillis()
                stalledTurn = false
                quietNoticed = false
                _ui.update { it.copy(streaming = true, error = null, pendingImage = null) }
            } catch (e: RpcException) {
                if (AnyChatSendRetry.isStaleSessionError(e.code, e.message)) {
                    healAfterStaleSession()
                } else {
                    _ui.update { st ->
                        st.copy(
                            streaming = false,
                            items = st.items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, "submit failed (${e.code}): ${e.message}"),
                        )
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(streaming = false, error = "submit failed: ${e.message}") }
            }
        }
    }

    /**
     * Live dogfood 2026-09-16 (Wi-Fi blip): the gateway reaped the ws-attached session
     * even with close_on_disconnect=false, so runtimeSessionId went stale and EVERY
     * send/steer/approval answered rpc 4001 "session not found" until the screen was torn
     * down and reopened — a zombie chat. Self-heal: re-run the full open (canonical resume,
     * else a fresh canonical session), then say honestly that the bounced message must be
     * resent. Runs in the caller's coroutine; open() restarts the event collectors itself.
     */
    private suspend fun healAfterStaleSession() {
        open()
        _ui.update { st ->
            st.copy(items = st.items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, ChatStream.RECONNECT_NOTICE))
        }
    }

    /**
     * A typed reply while a clarify card is pending would sit undelivered: prompt.submit
     * parks the turn server-side (server.py:1276) until clarify.respond or the ~5-min
     * timeout, so the composer's "reply below to answer" affordance must unblock first.
     * Desktop parity (store/clarify.ts skipClarifyRequest): an empty answer is the card's
     * own Skip; clarify.respond tolerates expiry, so racing the timeout is harmless.
     * Single-flight per request_id; a failed skip never swallows the message.
     */
    private suspend fun skipPendingClarify(sid: String) {
        val card = _ui.value.approval?.takeIf { it.kind == Catalog.EVENT_CLARIFY_REQUEST } ?: return
        if (!clarifySkipped.add(card.requestId)) return
        try {
            gateway?.request(
                Catalog.METHOD_CLARIFY_RESPOND,
                buildJsonObject {
                    put("session_id", sid)
                    put("request_id", card.requestId)
                    put("answer", "")
                },
            )
            _ui.update { st ->
                if (st.approval?.requestId == card.requestId) st.copy(approval = null) else st
            }
        } catch (_: Exception) {
            // Still submit below — the turn also times out server-side on its own.
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
            } catch (e: RpcException) {
                if (AnyChatSendRetry.isStaleSessionError(e.code, e.message)) {
                    healAfterStaleSession()
                } else {
                    _ui.update { it.copy(error = "Couldn't send that response.") }
                }
            } catch (_: Exception) {
                _ui.update { it.copy(error = "Couldn't send that response.") }
            }
        }
    }

    fun interrupt() {
        val sid = runtimeSessionId ?: return
        viewModelScope.launch {
            runCatching {
                gateway?.request(Catalog.METHOD_SESSION_INTERRUPT, buildJsonObject { put("session_id", sid) })
            }
            stalledTurn = false
            quietNoticed = false
            _ui.update { st ->
                st.copy(streaming = false, statusText = null, items = ChatStream.clearStallNotices(st.items))
            }
        }
    }

    /**
     * §5.1 session.steer {session_id, text}: inject typed text mid-turn (incident
     * 2026-09-16 — the only previous affordance was the IME action, and failures were
     * silently swallowed). The echo lands optimistically as a user-side ↗ item so the
     * user sees what was injected; the gateway streams the effect itself — no fake acks.
     * A failed steer surfaces an ErrorLine (app-authored text passes through
     * Humanize.appBanners untouched) instead of vanishing.
     */
    fun steer(rawText: String) {
        val sid = runtimeSessionId ?: return
        val text = rawText.trim()
        if (text.isEmpty()) return
        _ui.update { st -> st.copy(items = ChatStream.steerEcho(st.items, text) { "u-${++itemCounter}" }) }
        viewModelScope.launch {
            try {
                gateway?.request(
                    Catalog.METHOD_SESSION_STEER,
                    buildJsonObject { put("session_id", sid); put("text", text) },
                )
            } catch (e: RpcException) {
                if (AnyChatSendRetry.isStaleSessionError(e.code, e.message)) {
                    healAfterStaleSession()
                } else {
                    steerFailed()
                }
            } catch (_: Exception) {
                steerFailed()
            }
        }
    }

    private fun steerFailed() {
        _ui.update { st ->
            st.copy(
                items = st.items +
                    ChatItem("e-${++itemCounter}", ItemKind.ERROR, "Steer didn't reach $botName — try again."),
            )
        }
    }

    /** Remove a client-side artifact (inline error, stall notice, warning) from the transcript. */
    fun dismissItem(id: String) {
        _ui.update { st -> st.copy(items = st.items.filterNot { it.id == id }) }
    }

    /** Clear the humane banner — it described the previous attempt, not a permanent state. */
    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    /** TurnWatchdog declaration: free the composer and surface the dismissible notice. */
    private fun declareStall() {
        stalledTurn = true
        _ui.update { st ->
            st.copy(
                streaming = false,
                statusText = null,
                items = ChatStream.stallNotice(st.items) { "stall-${++itemCounter}" },
            )
        }
    }

    /** Queue an image (image.attach_bytes) to ride with the next send. */
    fun attachImageFromUri(uri: Uri) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { loadPendingImage(getApplication(), uri) }.getOrNull()
            }
            if (loaded != null) {
                _ui.update { it.copy(pendingImage = loaded, error = null) }
            } else {
                _ui.update { it.copy(error = "Couldn't use that image — try a different one.") }
            }
        }
    }

    fun clearPendingImage() {
        _ui.update { it.copy(pendingImage = null) }
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

    private fun boolField(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun loadPendingImage(context: android.content.Context, uri: Uri): PendingImage? {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val scale = minOf(1f, ATTACH_MAX_DIMEN.toFloat() / maxOf(decoded.width, decoded.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        return PendingImage(
            filename = queryDisplayName(context, uri) ?: "photo.jpg",
            base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
        )
    }

    private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    override fun onCleared() {
        eventJob?.cancel()
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        super.onCleared()
    }
}
