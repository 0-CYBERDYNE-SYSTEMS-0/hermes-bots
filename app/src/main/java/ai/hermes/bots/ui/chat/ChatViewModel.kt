package ai.hermes.bots.ui.chat

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AnyChatSendRetry
import ai.hermes.bots.data.ApprovalCard
import ai.hermes.bots.data.CanonicalChat
import ai.hermes.bots.data.clearSubmittedAttachment
import ai.hermes.bots.data.DiffText
import ai.hermes.bots.data.ChatItem
import ai.hermes.bots.data.ChatMessagesParser
import ai.hermes.bots.data.ChatStream
import ai.hermes.bots.data.ChatUiState
import ai.hermes.bots.data.ModelSwitchConfirmation
import ai.hermes.bots.data.ItemKind
import ai.hermes.bots.data.PendingImage
import ai.hermes.bots.data.PendingFile
import ai.hermes.bots.data.TodoState
import ai.hermes.bots.data.ToolResult
import ai.hermes.bots.data.TurnWatchdog
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.Auth
import ai.hermes.bots.protocol.GatewayEvent
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.RpcException
import ai.hermes.bots.protocol.SocketState
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request


/** Attachments are downscaled to this max dimension before JPEG q80 + base64 (server cap is 25 MB). */
private const val ATTACH_MAX_DIMEN = 1280

private class SlashCommandFailure(val userMessage: String) : Exception(userMessage)

private data class PendingModelSwitch(
    val sessionId: String,
    val profile: String,
    val provider: String,
    val model: String,
    val outcomeUnknown: Boolean = false,
)

internal fun canDispatchChatSend(
    sessionOpen: Boolean,
    loading: Boolean,
    modelAlignmentPending: Boolean,
    sending: Boolean = false,
): Boolean = sessionOpen && !loading && !modelAlignmentPending && !sending


class ChatViewModel(
    app: Application,
    private val connectionId: String,
    private val botName: String,
) : AndroidViewModel(app) {

    private val graph = (app as HermesBotsApp).graph
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui

    /** Last model the gateway announced for this live session (session.info). */
    private val announcedPin = MutableStateFlow("" to "")
    private var sentWithPin = "" to ""

    // SV-01: true only after the session successfully opened; reset when open fails.
    private val _sessionOpen = MutableStateFlow(false)
    private val _modelAlignmentPending = MutableStateFlow(false)

    /** The composer waits until a saved-model alignment attempt reaches a terminal state. */
    val canSend: StateFlow<Boolean> = combine(_ui, _sessionOpen, _modelAlignmentPending) { st, open, pending ->
        canDispatchChatSend(open, st.loading, pending)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Presentation-only receive stamps for time separators: item id → wall clock
    // at first appearance. History from session.resume gets "now" — gaps still separate turns.
    private val _itemTimes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val itemTimes: StateFlow<Map<String, Long>> = _itemTimes

    /** Header presence line: "Active now" / "Idle · seen 17h" — never the model slug. */
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

    /** The owning gateway's label when this bot's name collides across gateways. */
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
    private var commandJob: Job? = null
    private var pendingModelSwitch: PendingModelSwitch? = null
    private var deferredModelSwitch: PendingModelSwitch? = null
    private var modelSwitchCheckJob: Job? = null
    private var completedTurnCount = 0

    // Last session-event receive stamp and one-shot latches for the turn watchdog.
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
            _sessionOpen.value = false
            _modelAlignmentPending.value = true
            pendingModelSwitch = null
            deferredModelSwitch = null
            modelSwitchCheckJob?.cancel()
            modelSwitchCheckJob = null
            _ui.update {
                it.copy(
                    loading = true,
                    error = null,
                    modelReady = false,
                    modelSwitchDeferred = false,
                    modelSwitchConfirmation = null,
                    commandFeedback = null,
                    commandFeedbackIsError = false,
                )
            }
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
            var opened: JsonObject? = null
            if (canonicalId != null) {
                opened = try {
                    // Server ≥0.21.1 resolves profile-scoped sessions only when the profile is named.
                    gw.request(
                        Catalog.METHOD_SESSION_RESUME,
                        buildJsonObject { put("session_id", canonicalId); put("profile", botName) },
                        120_000,
                    )
                } catch (_: Exception) {
                    null // canonical session gone — fall through to create
                }
            }
            val session = opened ?: gw.request(
                Catalog.METHOD_SESSION_CREATE,
                CanonicalChat.createParams(botName, row.model, row.provider),
                120_000,
            )
            adopt(session)
            announcedPin.value = "" to ""
            _ui.update { it.copy(botModel = null, botProvider = null) }
            startCollectors()
            viewModelScope.launch { alignRunningModel(gw, row, session) }
        } catch (e: Exception) {
            _sessionOpen.value = false
            _modelAlignmentPending.value = false
            _ui.update { it.copy(loading = false, error = e.message ?: "failed to open chat") }
        }
    }

    /**
     * The resume payload can name the saved pin while the first agent build still
     * uses the stored session model. Wait for that build, then switch or queue the
     * saved model before allowing the next chat turn.
     */
    private suspend fun alignRunningModel(gw: HermesGateway, row: ai.hermes.bots.data.BotRow, opened: JsonObject) {
        var keepSendGate = false
        try {
            val savedModel = row.model?.trim().orEmpty()
            val savedProvider = row.provider?.trim().orEmpty()
            if (savedModel.isBlank() || savedProvider.isBlank()) {
                val pin = CanonicalChat.runningPin(opened)
                showPin(pin.second, pin.first, ready = true, error = null)
                return
            }
            fun matches(pin: Pair<String, String>) =
                pin.first.isNotBlank() &&
                    !CanonicalChat.pinMismatch(pin.first, pin.second, savedModel, savedProvider)
            val sid = runtimeSessionId
            if (sid == null) {
                _ui.update { it.copy(error = "This chat has no live session to switch.") }
                return
            }
            // The built agent announces itself on session.info. Resume info can name the
            // saved pin while that build still uses the stored session model.
            val built = withTimeoutOrNull(8_000) {
                announcedPin.first { it.first.isNotBlank() }
            }
            if (built == null) {
                val pin = CanonicalChat.runningPin(opened)
                showPin(
                    pin.second,
                    pin.first,
                    ready = false,
                    error = "Couldn't confirm the live model. The bot is set to $savedProvider/$savedModel; sending is available.",
                )
                return
            }
            var confirmed: Pair<String, String>? = built.takeIf { matches(it) }
            val last = if (confirmed == null) {
                val completedBeforeSwitch = completedTurnCount
                switchToSaved(gw, sid, row.name, savedProvider, savedModel).also { result ->
                    if (result.deferred) {
                        keepSendGate = true
                        holdForDeferredModelSwitch(
                            PendingModelSwitch(sid, row.name, savedProvider, savedModel),
                            completedBeforeSwitch,
                        )
                        return
                    }
                }
            } else CanonicalChat.AlignResult(true, null)
            if (last.confirmationRequired) {
                pendingModelSwitch = PendingModelSwitch(sid, row.name, savedProvider, savedModel)
                _ui.update {
                    it.copy(
                        modelReady = false,
                        modelSwitchConfirmation = ModelSwitchConfirmation(
                            provider = savedProvider,
                            model = savedModel,
                            message = last.detail ?: "The gateway requires confirmation before switching this model.",
                        ),
                        error = null,
                    )
                }
                keepSendGate = true
                return
            }
            if (last.outcomeUnknown) {
                keepSendGate = true
                val now = announcedPin.value
                showPin(
                    now.second,
                    now.first,
                    ready = false,
                    error = "Couldn't confirm whether the saved-model switch was applied. Retry before sending.",
                )
                return
            }
            if (last.accepted && confirmed == null) {
                showPin(savedProvider, savedModel, ready = true, error = null)
                return
            }
            val confirmedPin = confirmed
            if (confirmedPin != null) {
                showPin(confirmedPin.second, confirmedPin.first, ready = true, error = null)
            } else {
                val now = announcedPin.value
                val why = last.detail?.let { " $it" }.orEmpty()
                showPin(
                    now.second,
                    now.first,
                    ready = false,
                    error = "This chat is on ${now.second.ifBlank { "unknown" }}/${now.first.ifBlank { "unknown" }}. " +
                        "The bot is set to $savedProvider/$savedModel.$why",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val pin = announcedPin.value
            showPin(
                pin.second,
                pin.first,
                ready = false,
                error = "Couldn't confirm the live model. Sending is available after this check.",
            )
        } finally {
            if (!keepSendGate) _modelAlignmentPending.value = false
        }
    }

    private fun holdForDeferredModelSwitch(pending: PendingModelSwitch, completedBeforeSwitch: Int) {
        deferredModelSwitch = pending
        _ui.update {
            it.copy(
                modelReady = false,
                modelSwitchDeferred = true,
                modelSwitchConfirmation = null,
                commandFeedback = "The gateway queued ${pending.provider}/${pending.model} for the next turn. " +
                    "This turn will finish with its active model.",
                commandFeedbackIsError = false,
            )
        }
        if (completedTurnCount > completedBeforeSwitch) finishDeferredModelSwitch()
        else watchDeferredModelSwitch(pending)
    }

    private fun watchDeferredModelSwitch(pending: PendingModelSwitch) {
        val gw = gateway ?: return
        modelSwitchCheckJob?.cancel()
        modelSwitchCheckJob = viewModelScope.launch {
            while (deferredModelSwitch == pending) {
                delay(5_000)
                val status = try {
                    gw.request(
                        Catalog.METHOD_SESSION_RESUME,
                        buildJsonObject {
                            put("session_id", pending.sessionId)
                            put("profile", pending.profile)
                            put("omit_messages", true)
                        },
                        8_000,
                    )
                } catch (_: TimeoutCancellationException) {
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val running = (status?.get("running") as? JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull()
                if (running == false) {
                    finishDeferredModelSwitch()
                    return@launch
                }
            }
        }
    }

    private fun finishDeferredModelSwitch() {
        if (deferredModelSwitch == null) return
        deferredModelSwitch = null
        _ui.update { it.copy(modelReady = true, modelSwitchDeferred = false) }
        _modelAlignmentPending.value = false
        modelSwitchCheckJob?.cancel()
        modelSwitchCheckJob = null
    }

    private suspend fun switchToSaved(
        gw: HermesGateway,
        sessionId: String,
        profile: String,
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean = false,
    ): CanonicalChat.AlignResult = try {
        CanonicalChat.alignSession(sessionId, profile, provider, model, confirmExpensiveModel) { params ->
            gw.request(Catalog.METHOD_CONFIG_SET, params, 60_000)
        }
    } catch (e: TimeoutCancellationException) {
        CanonicalChat.AlignResult(false, e.message, outcomeUnknown = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CanonicalChat.AlignResult(false, e.message, outcomeUnknown = true)
    }

    fun confirmModelSwitch() {
        val pending = pendingModelSwitch ?: return
        val gw = gateway ?: return
        if (_ui.value.modelSwitchConfirmation?.switching == true) return
        viewModelScope.launch {
            val completedBeforeSwitch = completedTurnCount
            _ui.update { state ->
                state.copy(modelSwitchConfirmation = state.modelSwitchConfirmation?.copy(switching = true, error = null))
            }
            val result = switchToSaved(
                gw,
                pending.sessionId,
                pending.profile,
                pending.provider,
                pending.model,
                confirmExpensiveModel = true,
            )
            if (result.deferred) {
                pendingModelSwitch = null
                holdForDeferredModelSwitch(pending, completedBeforeSwitch)
            } else if (result.accepted) {
                pendingModelSwitch = null
                _ui.update { it.copy(modelSwitchConfirmation = null) }
                showPin(pending.provider, pending.model, ready = true, error = null)
                _modelAlignmentPending.value = false
            } else {
                if (result.outcomeUnknown) pendingModelSwitch = pending.copy(outcomeUnknown = true)
                val error = if (result.outcomeUnknown) {
                    "The gateway may have accepted the switch, but its response was lost. Retry to resolve it; " +
                        "keeping the current model is unavailable until the gateway confirms."
                } else if (result.confirmationRequired) {
                    result.detail ?: "The gateway still requires confirmation."
                } else if (!result.accepted) {
                    "Couldn't switch to the saved model. Try again or keep the current model."
                } else {
                    "Couldn't confirm that the gateway applied the model switch. Try again or keep the current model."
                }
                _ui.update { state ->
                    state.copy(
                        modelSwitchConfirmation = state.modelSwitchConfirmation?.copy(
                            switching = false,
                            error = error,
                            canKeepCurrent = !pending.outcomeUnknown && !result.outcomeUnknown,
                        ),
                    )
                }
            }
        }
    }

    fun keepCurrentModel() {
        if (_ui.value.modelSwitchConfirmation?.switching == true) return
        if (_ui.value.modelSwitchConfirmation?.canKeepCurrent == false) return
        if (pendingModelSwitch == null) return
        val pin = announcedPin.value
        pendingModelSwitch = null
        _ui.update { it.copy(modelSwitchConfirmation = null) }
        showPin(pin.second, pin.first, ready = true, error = null)
        _modelAlignmentPending.value = false
    }

    private fun showPin(provider: String, model: String, ready: Boolean, error: String?) {
        _ui.update {
            it.copy(
                botProvider = provider.ifBlank { null },
                botModel = model.ifBlank { null },
                modelReady = ready,
                modelSwitchDeferred = false,
                error = error,
            )
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
        ) ?: run {
            // 0.21.3+ dialect: unanswered blocking prompts come back as `open_requests`
            // (server_requests.py snapshot) — surface the first one as a pinned card.
            (result["open_requests"] as? JsonArray)
                ?.asSequence()
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { entry ->
                    val method = (entry["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    method?.substringBeforeLast('.') in ai.hermes.bots.protocol.BLOCKING_SERVER_REQUEST_METHODS
                }
                ?.let { entry ->
                    val id = (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val params = entry["params"] as? JsonObject
                    val method = (entry["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (id != null && params != null && method != null) {
                        CanonicalChat.parseCard(
                            "${method.substringBeforeLast('.')}.request",
                            JsonObject(params + mapOf("request_id" to JsonPrimitive(id), "server_request" to JsonPrimitive(true))),
                        )
                    } else {
                        null
                    }
                }
        }
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
                // A pending prompt parks the turn (server.py:1276) — never treat a resumed
                // session with a card as still streaming, or the composer lands in steer mode.
                streaming = running && card == null,
                // PROTOCOL.md §5.2: resume may return todo_state? — restore the plan card
                // so a reconnect mid-turn doesn't lose the checklist (TodoState renders
                // nothing when the shape doesn't parse).
                todo = TodoState.parse(result["todo_state"]),
            )
        }
    }

    private fun startCollectors() {
        val gw = gateway ?: return
        val sid = runtimeSessionId ?: return
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            gw.events.collect { ev ->
                val reclaimedId = (ev.payload["session_id"] as? JsonPrimitive)?.content
                val canonicalId = graph.roster.roster.value
                    .firstOrNull { it.bot.connectionId == connectionId && it.bot.name == botName }
                    ?.bot?.canonicalSessionId
                if (ev.sessionId == sid ||
                    (ev.type == Catalog.EVENT_SESSION_RECLAIMED &&
                        (reclaimedId == sid || reclaimedId == canonicalId))
                ) onEvent(ev)
            }
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
            // The gateway may expire a WebSocket-attached session while the socket is down;
            // reopen it rather than leaving every send to fail with a stale-session error.
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
                // Keep gateway details out of the user-facing banner.
                _ui.update { it.copy(error = "Couldn't load the conversation.") }
            }
        } catch (_: Exception) {
            // Keep technical details out of the user-facing banner.
            _ui.update { it.copy(error = "Couldn't load the conversation.") }
        }
    }

    private suspend fun resumeCanonical() {
        val gw = gateway ?: return
        val canonicalId = graph.roster.roster.value
            .firstOrNull { it.bot.connectionId == connectionId && it.bot.name == botName }
            ?.bot?.canonicalSessionId ?: return
        runCatching {
            gw.request(Catalog.METHOD_SESSION_RESUME, buildJsonObject {
                put("session_id", canonicalId)
                put("profile", botName)
            }, 120_000)
        }.onSuccess {
            adopt(it)
            startCollectors()
        }.onFailure { _ui.update { st -> st.copy(error = "Couldn't resume the conversation.") } }
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
            Catalog.EVENT_SESSION_RECLAIMED -> viewModelScope.launch { resumeCanonical() }
            "agent.terminal.output" -> {
                val lines = ((ev.payload["backlog"] ?: ev.payload["lines"]) as? JsonArray)
                    ?.mapNotNull { entry ->
                        (entry as? JsonPrimitive)?.content
                            ?: ((entry as? JsonObject)?.get("text") as? JsonPrimitive)?.content
                    }
                    .orEmpty() + listOfNotNull(str("chunk") ?: str("text") ?: str("output"))
                if (lines.isNotEmpty()) _ui.update { st ->
                    st.copy(runLines = (st.runLines + lines).takeLast(500))
                }
            }
            Catalog.EVENT_SESSION_INFO -> {
                val pin = CanonicalChat.runningPin(ev.payload)
                if (pin.first.isNotBlank() || pin.second.isNotBlank()) {
                    announcedPin.value = pin
                    _ui.update { st ->
                        st.copy(
                            botModel = pin.first.ifBlank { null },
                            botProvider = pin.second.ifBlank { null },
                        )
                    }
                }
            }
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
                // Seal the interim as its own segment — never overwrite
                // the streamed anchor, never duplicate already-streamed text.
                st.copy(items = ChatStream.sealInterim(
                    st.items,
                    str("text") ?: "",
                    boolField(ev.payload, "already_streamed") == true,
                ) { "a-${++itemCounter}" })
            }
            Catalog.EVENT_MESSAGE_COMPLETE -> {
                completedTurnCount++
                _ui.update { st ->
                    // Complete replaces only the newest live anchor; sealed segments survive.
                    var items = ChatStream.completeAnchor(st.items, str("text") ?: "") { "a-${++itemCounter}" }
                    var modelError: String? = null
                    val errText = str("error")
                    val status = str("status")
                    if (errText != null || status == "error") {
                        val model = "${sentWithPin.second.ifBlank { "unknown" }}/${sentWithPin.first.ifBlank { "unknown" }}"
                        items = items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, "${errText ?: "turn error"} (model: $model)")
                        val saved = graph.roster.roster.value.firstOrNull { it.bot.connectionId == connectionId && it.bot.name == botName }?.bot
                        if (saved != null && CanonicalChat.pinMismatch(sentWithPin.first, sentWithPin.second, saved.model.orEmpty(), saved.provider.orEmpty())) {
                            modelError = "Turn ran on $model; bot is set to ${saved.provider}/${saved.model}."
                        }
                    }
                    // PROTOCOL.md §5.1/§6: message.complete may carry `warning?` — previously
                    // dropped on the floor. Rendered as a dismissible verbatim system line.
                    val warning = str("warning")
                    if (!warning.isNullOrBlank()) {
                        items = items + ChatItem("warn-${++itemCounter}", ItemKind.ERROR, warning)
                    }
                    st.copy(items = items, streaming = false, statusText = null, error = modelError ?: st.error)
                }
                finishDeferredModelSwitch()
            }
            Catalog.EVENT_TOOL_START -> _ui.update { st ->
                val toolId = (ev.payload["tool_id"] as? JsonPrimitive)?.content ?: "n${itemCounter + 1}"
                st.copy(
                    items = st.items + ChatItem(
                        id = "tool-$toolId",
                        kind = ItemKind.TOOL,
                        // Non-verbose sessions omit args_text; fall back to the command
                        // inside the always-present `args` payload.
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
                            // result_text is verbose-only on the wire; flatten
                            // the always-present `result` so terminal chips can expand.
                            outputText = ToolResult.displayText(ev.payload["result"]),
                            // Extract optional inline_diff; leave it null when absent or unrecognized.
                            inlineDiff = DiffText.extract(ev.payload["inline_diff"]),
                        )
                    }
                } else {
                    st.items
                }
                st.copy(items = items)
            }
            Catalog.EVENT_STATUS_UPDATE -> _ui.update { it.copy(statusText = str("text")) }
            Catalog.EVENT_TODO_UPDATED -> _ui.update {
                // A payload we cannot read renders no checklist rather than guessing at a
                // shape the protocol does not specify.
                it.copy(todo = TodoState.parse(ev.payload))
            }
            Catalog.EVENT_APPROVAL_REQUEST,
            Catalog.EVENT_CLARIFY_REQUEST,
            Catalog.EVENT_SUDO_REQUEST,
            Catalog.EVENT_SECRET_REQUEST,
            -> _ui.update {
                // A blocking prompt parks the turn server-side; it is not a running turn.
                // Keeping streaming=true would leave the composer in steer/stop mode, so a
                // choice-less Question card would say
                // "reply below to answer" while every typed reply steered a parked turn into
                // the void. Drop to send mode and retire the Working strip with the turn.
                it.copy(
                    approval = CanonicalChat.parseCard(ev.type, ev.payload),
                    approvalResolved = null,
                    approvalExpired = false,
                    streaming = false,
                    statusText = null,
                )
            }
            Catalog.EVENT_SESSION_TITLE -> _ui.update { it.copy(sessionTitle = str("title")) }
            Catalog.EVENT_ERROR -> {
                completedTurnCount++
                _ui.update { st ->
                    st.copy(
                        items = st.items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, str("message") ?: "error"),
                        streaming = false,
                        statusText = null,
                    )
                }
                finishDeferredModelSwitch()
            }
            else -> {
                when {
                    // 0.21.3+ dialect: timeout/interrupt/cancel of a srq request (legacy uses *.expire).
                    ev.type == "request.cancel" -> {
                        val rid = str("id")
                        _ui.update { st ->
                            when {
                                st.approval?.serverRequestId != null && st.approval?.serverRequestId == rid ->
                                    st.copy(approval = null, approvalExpired = true, approvalResolved = null)
                                else -> st
                            }
                        }
                    }
                    ev.type.endsWith(".expire") -> {
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
    }

    fun dismissCommandFeedback() {
        _ui.update { it.copy(commandFeedback = null, commandFeedbackIsError = false) }
    }

    fun send(
        rawText: String,
        onAccepted: () -> Unit = {},
        onPrefill: (String) -> Unit = {},
    ) {
        val state = _ui.value
        if (!canDispatchChatSend(_sessionOpen.value, state.loading, _modelAlignmentPending.value, state.sending)) return
        if (commandJob?.isActive == true) return
        val sid = runtimeSessionId ?: return
        val text = rawText.trim()
        val pending = _ui.value.pendingImage
        val pendingFile = _ui.value.pendingFile
        if (text.isEmpty() && pending == null && pendingFile == null) return

        val slash = CanonicalChat.parseSlashCommand(text)
        if (slash != null) {
            if (pending != null || pendingFile != null) {
                _ui.update { it.copy(error = "Remove attachments before running a slash command.") }
            }
            if (pending != null || pendingFile != null || commandJob?.isActive == true) return
            commandJob = viewModelScope.launch {
                _ui.update {
                    it.copy(
                        error = null,
                        commandFeedback = null,
                        commandFeedbackIsError = false,
                        commandRunning = "/${slash.name}",
                    )
                }
                try {
                    runSlashCommand(sid, slash, onAccepted, onPrefill)
                } catch (_: TimeoutCancellationException) {
                    _ui.update {
                        it.copy(
                            commandFeedback = "Couldn't confirm whether /${slash.name} completed. Refresh the chat and check before retrying.",
                            commandFeedbackIsError = true,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = when (e) {
                        is SlashCommandFailure -> e.userMessage
                        is RpcException -> when (e.code) {
                            4009, 4091 -> "This bot is working. Wait for the turn to finish, then retry /${slash.name}."
                            4018 -> "/${slash.name} isn't available on this gateway. Your command is still in the composer."
                            Catalog.ERR_METHOD_NOT_FOUND -> "This gateway doesn't support slash commands. Your command is still in the composer."
                            else -> "Couldn't run /${slash.name}. Check the gateway connection and try again."
                        }
                        else -> "Couldn't confirm whether /${slash.name} completed. Refresh the chat and check before retrying."
                    }
                    _ui.update { it.copy(commandFeedback = message, commandFeedbackIsError = true) }
                } finally {
                    _ui.update { it.copy(commandRunning = null) }
                }
            }
            return
        }
        if (commandJob?.isActive == true) return
        _ui.update { it.copy(commandFeedback = null, commandFeedbackIsError = false, sending = true) }
        viewModelScope.launch {
            skipPendingClarify(sid)
            val bubble = when {
                pending != null && text.isNotEmpty() -> "$text\n🖼 ${pending.filename}"
                pending != null -> "🖼 ${pending.filename}"
                pendingFile != null -> "📎 ${pendingFile.filename}"
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
                if (pendingFile != null) {
                    gateway?.request(
                        Catalog.METHOD_FILE_ATTACH,
                        CanonicalChat.fileAttachParams(sid, pendingFile.dataUrl),
                        120_000,
                    )
                }
                // Image-only sends ride the server's own attachment turn-text convention.
                val submitText = text.ifBlank {
                    if (pendingFile != null) "[User attached file: ${pendingFile.filename}]"
                    else "[User attached image: ${pending?.filename}]"
                }
                sentWithPin = announcedPin.value
                gateway?.request(Catalog.METHOD_PROMPT_SUBMIT, CanonicalChat.submitParams(sid, submitText), 30_000)
                onAccepted()
                lastActivityMs = System.currentTimeMillis()
                stalledTurn = false
                quietNoticed = false
                _ui.update {
                    it.copy(
                        streaming = true,
                        sending = false,
                        error = null,
                        pendingImage = clearSubmittedAttachment(it.pendingImage, pending),
                        pendingFile = clearSubmittedAttachment(it.pendingFile, pendingFile),
                    )
                }
            } catch (e: RpcException) {
                if (AnyChatSendRetry.isStaleSessionError(e.code, e.message)) {
                    healAfterStaleSession()
                } else {
                    val message = when (e.code) {
                        4090 -> "This gateway is at its session cap."
                        4091 -> "This bot is mid-turn."
                        else -> "submit failed (${e.code}): ${e.message}"
                    }
                    _ui.update { st ->
                        st.copy(
                            streaming = false,
                            items = st.items + ChatItem("e-${++itemCounter}", ItemKind.ERROR, message),
                        )
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(streaming = false, sending = false, error = "submit failed: ${e.message}") }
            } finally {
                _ui.update { it.copy(sending = false) }
            }
        }
    }

    private suspend fun runSlashCommand(
        sessionId: String,
        initial: ai.hermes.bots.data.SlashCommand,
        onAccepted: () -> Unit,
        onPrefill: (String) -> Unit,
    ) {
        val gw = gateway ?: error("Gateway is not ready")
        var command = initial
        val visited = mutableSetOf<String>()
        repeat(8) {
            val key = "${command.name}\u0000${command.argument}"
            check(visited.add(key)) { "Slash command alias loop" }
            when (command.name) {
                "new" -> {
                    if (command.argument.isNotBlank()) {
                        throw SlashCommandFailure("In a canonical bot chat, /new doesn't accept a focus topic. Use /compress <topic>.")
                    }
                    runCompression(gw, sessionId, command, null, onAccepted)
                    return
                }
                "compress", "compact" -> {
                    if (CanonicalChat.isCompressionPreview(command.argument)) {
                        runCompressionPreview(gw, sessionId, command, onAccepted)
                    } else {
                        runCompression(gw, sessionId, command, command.argument, onAccepted)
                    }
                    return
                }
            }

            val response = try {
                gw.request(Catalog.METHOD_SLASH_EXEC, CanonicalChat.slashExecParams(sessionId, command))
            } catch (slashFailure: RpcException) {
                if (!CanonicalChat.shouldFallbackSlashDispatch(slashFailure)) throw slashFailure
                try {
                    gw.request(Catalog.METHOD_COMMAND_DISPATCH, CanonicalChat.commandDispatchParams(sessionId, command))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (dispatchFailure: Exception) {
                    if (dispatchFailure is RpcException && dispatchFailure.code == Catalog.ERR_METHOD_NOT_FOUND) {
                        throw SlashCommandFailure(
                            "This gateway doesn't support command dispatch. No command was run; your command is still in the composer.",
                        )
                    }
                    throw SlashCommandFailure(
                        "Couldn't confirm whether /${command.name} completed. Refresh the chat and check before retrying.",
                    )
                }
            }
            when (val directive = CanonicalChat.parseSlashDirective(response)
                ?: error("Invalid slash command response")) {
                is ai.hermes.bots.data.SlashDirective.Alias -> {
                    val target = directive.target.trim().trimStart('/')
                    command = CanonicalChat.parseSlashCommand("/$target ${command.argument}")
                        ?: error("Invalid slash command alias")
                }
                is ai.hermes.bots.data.SlashDirective.Output -> {
                    onAccepted()
                    val text = buildList {
                        directive.warning?.takeIf { it.isNotBlank() }?.let { add("Warning: $it") }
                        directive.text.takeIf { it.isNotBlank() }?.let(::add)
                    }.joinToString("\n").ifBlank { "/${command.name}: no output" }
                    _ui.update { it.copy(commandFeedback = text, commandFeedbackIsError = false, error = null) }
                    return
                }
                is ai.hermes.bots.data.SlashDirective.Prefill -> {
                    onAccepted()
                    onPrefill(directive.message)
                    _ui.update {
                        it.copy(
                            commandFeedback = directive.notice ?: "Prepared the command in the composer.",
                            commandFeedbackIsError = false,
                            error = null,
                        )
                    }
                    return
                }
                is ai.hermes.bots.data.SlashDirective.Submit -> {
                    submitSlashPrompt(sessionId, command, directive, onAccepted)
                    return
                }
            }
        }
        error("Too many slash command aliases")
    }

    private suspend fun runCompressionPreview(
        gw: HermesGateway,
        sessionId: String,
        command: ai.hermes.bots.data.SlashCommand,
        onAccepted: () -> Unit,
    ) {
        val previewCommand = command.copy(name = "compress")
        val response = try {
            gw.request(
                Catalog.METHOD_SLASH_EXEC,
                CanonicalChat.slashExecParams(sessionId, previewCommand),
            )
        } catch (e: TimeoutCancellationException) {
            throw SlashCommandFailure("Couldn't confirm the /${command.name} preview. No Android compression was requested.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw SlashCommandFailure("Couldn't prepare the preview. Check the gateway connection and try again.")
        }
        val output = CanonicalChat.parseSlashDirective(response) as? ai.hermes.bots.data.SlashDirective.Output
            ?: throw SlashCommandFailure("This gateway didn't return a compression preview. No changes were made.")
        onAccepted()
        val text = output.text.ifBlank { "/${command.name}: no preview output" }
        _ui.update {
            it.copy(
                commandFeedback = listOfNotNull(output.warning?.takeIf(String::isNotBlank)?.let { "Warning: $it" }, text)
                    .joinToString("\n"),
                commandFeedbackIsError = false,
                error = null,
            )
        }
    }

    private suspend fun runCompression(
        gw: HermesGateway,
        sessionId: String,
        command: ai.hermes.bots.data.SlashCommand,
        focusTopic: String?,
        onAccepted: () -> Unit,
    ) {
        val result = try {
            gw.request(
                Catalog.METHOD_SESSION_COMPRESS,
                CanonicalChat.compressParams(sessionId, focusTopic),
                Catalog.SESSION_COMPRESS_TIMEOUT_MS,
            )
        } catch (e: RpcException) {
            if (e.code == 4009 || e.code == 4091) {
                throw SlashCommandFailure("This bot is working. Wait for the turn to finish, then retry /${command.name}.")
            }
            if (AnyChatSendRetry.isStaleSessionError(e.code, e.message)) {
                healAfterStaleSession()
                throw SlashCommandFailure("The chat reconnected. Review it, then retry /${command.name} if needed.")
            }
            throw SlashCommandFailure("Couldn't confirm whether /${command.name} finished. Reopen the chat to refresh before retrying.")
        } catch (_: TimeoutCancellationException) {
            throw SlashCommandFailure("Couldn't confirm whether /${command.name} finished. Reopen the chat to refresh before retrying.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw SlashCommandFailure("Couldn't confirm whether /${command.name} finished. Reopen the chat to refresh before retrying.")
        }
        onAccepted()
        _ui.update {
            it.copy(
                commandFeedback = CanonicalChat.compressionFeedback(result),
                commandFeedbackIsError = false,
                error = null,
            )
        }
        reload()
    }

    private suspend fun submitSlashPrompt(
        sessionId: String,
        command: ai.hermes.bots.data.SlashCommand,
        directive: ai.hermes.bots.data.SlashDirective.Submit,
        onAccepted: () -> Unit,
    ) {
        skipPendingClarify(sessionId)
        val display = directive.display?.takeIf { it.isNotBlank() }
            ?: "/${command.name} ${command.argument}".trim()
        _ui.update { st ->
            st.copy(items = finalizeStreaming(st.items) + ChatItem("u-${++itemCounter}", ItemKind.USER, display))
        }
        if (!directive.notice.isNullOrBlank()) {
            _ui.update { it.copy(commandFeedback = directive.notice) }
        }
        sentWithPin = announcedPin.value
        gateway?.request(Catalog.METHOD_PROMPT_SUBMIT, CanonicalChat.submitParams(sessionId, directive.message), 30_000)
        onAccepted()
        lastActivityMs = System.currentTimeMillis()
        stalledTurn = false
        quietNoticed = false
        _ui.update { it.copy(streaming = true, error = null) }
    }

    /**
     * Reopen a WebSocket-attached session after the gateway reports it is stale, then tell the
     * user to resend the message that failed. This runs in the caller's coroutine; open()
     * restarts the event collectors.
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
            if (card.serverRequestId != null) {
                // 0.21.3+ dialect: the skip is the JSON-RPC result of the srq request.
                gateway?.respondServerRequest(card.serverRequestId, clarifyResultFrame(card, ""))
            } else {
                gateway?.request(
                    Catalog.METHOD_CLARIFY_RESPOND,
                    buildJsonObject {
                        put("session_id", sid)
                        put("request_id", card.requestId)
                        put("answer", "")
                    },
                )
            }
            _ui.update { st ->
                if (st.approval?.requestId == card.requestId) st.copy(approval = null) else st
            }
        } catch (_: Exception) {
            // Still submit below — the turn also times out server-side on its own.
        }
    }

    /**
     * Result frame body for a 0.21.3+ clarify server request: `{"answer"}` for a single
     * question, `{"answers": {qid: …}}` for a batch (tui_gateway/server.py `_clarify_block`).
     * An empty answer is the card's own Skip (desktop parity).
     */
    private fun clarifyResultFrame(card: ApprovalCard, answer: String) = when (card.qid) {
        null -> buildJsonObject { put("answer", answer) }
        else -> buildJsonObject {
            put("answers", buildJsonObject { put(card.qid, answer) })
        }
    }

    /**
     * The choice-less Question card's own Skip (an empty answer unblocks the parked turn).
     * Only fires with a live session; when the session
     * is down the card's X covers hiding it.
     */
    fun skipClarify() {
        val sid = runtimeSessionId ?: return
        viewModelScope.launch { skipPendingClarify(sid) }
    }

    fun respond(choice: String, answers: Map<String, String> = emptyMap()) {
        val sid = runtimeSessionId ?: return
        val card = _ui.value.approval ?: return
        viewModelScope.launch {
            try {
                if (card.kind == Catalog.EVENT_CLARIFY_REQUEST) {
                    if (card.serverRequestId != null) {
                        // 0.21.3+ dialect: the answer is the JSON-RPC result of the srq request.
                        gateway?.respondServerRequest(card.serverRequestId, if (answers.isEmpty()) clarifyResultFrame(card, choice) else buildJsonObject {
                            put("answers", buildJsonObject { answers.forEach { (qid, answer) -> put(qid, answer) } })
                        })
                    } else {
                        gateway?.request(
                            Catalog.METHOD_CLARIFY_RESPOND,
                            buildJsonObject {
                                put("session_id", sid)
                                put("request_id", card.requestId)
                                if (answers.isEmpty()) put("answer", choice)
                                else put("answers", buildJsonObject { answers.forEach { (qid, answer) -> put(qid, answer) } })
                            },
                        )
                    }
                } else if (card.kind == Catalog.EVENT_SECRET_REQUEST) {
                    if (card.serverRequestId != null) {
                        gateway?.respondServerRequest(card.serverRequestId, buildJsonObject { put("answer", choice) })
                    } else {
                        gateway?.request(Catalog.METHOD_SECRET_RESPOND, buildJsonObject {
                            put("session_id", sid)
                            put("request_id", card.requestId)
                            put("answer", choice)
                        })
                    }
                } else if (card.serverRequestId != null) {
                    // Approval/sudo/secret mirror their legacy `choice` field in the result.
                    gateway?.respondServerRequest(
                        card.serverRequestId,
                        buildJsonObject { put("choice", choice) },
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
                _ui.update { it.copy(approval = null, approvalResolved = if (card.kind == Catalog.EVENT_SECRET_REQUEST) "Submitted" else choice) }
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
     * §5.1 session.steer {session_id, text}: inject typed text mid-turn. The echo lands
     * optimistically as a user-side ↗ item so the
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

    fun attachFileFromUri(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "document"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("File unavailable")
                    require(bytes.size <= 50 * 1024 * 1024) { "File exceeds 50 MiB" }
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    PendingFile(filename, "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
                }
            }
            file.onSuccess { loaded -> _ui.update { it.copy(pendingFile = loaded, error = null) } }
                .onFailure { failure -> _ui.update { it.copy(error = failure.message ?: "Couldn't attach that file.") } }
        }
    }

    fun clearPendingFile() { _ui.update { it.copy(pendingFile = null) } }

    fun openFileLink(url: String): Boolean {
        val link = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (link.path != Catalog.REST_FILES_DOWNLOAD) return false
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val connection = graph.connections.connections.first().first { it.id == connectionId }
                val base = java.net.URI(connection.baseUrl)
                val resolved = base.resolve(link)
                require(resolved.host == base.host && resolved.port == base.port) { "File link is from another gateway" }
                val client = OkHttpClient.Builder().cookieJar(Auth.COOKIE_JAR).build()
                val request = Request.Builder().url(resolved.toString()).apply {
                    Auth.restHeaders(connection.auth).forEach { (name, value) -> header(name, value) }
                }.build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Download failed (${response.code})" }
                    val body = response.body ?: error("Empty download")
                    val filename = link.path.substringAfterLast('/').ifBlank { "bot-file" }
                    val resolver = getApplication<Application>().contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, response.header("Content-Type") ?: "application/octet-stream")
                    }
                    val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Cannot save download")
                    resolver.openOutputStream(target)?.use { output -> body.byteStream().copyTo(output) }
                        ?: error("Cannot write download")
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = response.header("Content-Type") ?: "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, target)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    withContext(Dispatchers.Main) {
                        getApplication<Application>().startActivity(Intent.createChooser(share, "Share file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }.onFailure { failure -> _ui.update { it.copy(error = failure.message ?: "Couldn't download that file.") } }
        }
        return true
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
