package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.GatewayEvent
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.SocketState
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private val Context.anyChatStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(name = "anychat")

@Serializable
data class AnyChatMember(
    val connectionId: String,
    val botName: String,
) {
    val key: String get() = "$connectionId:$botName"
}

/** Client-side room over multiple per-bot canonical sessions. */
@Serializable
data class AnyChatRoom(
    val id: String,
    val name: String,
    val members: List<AnyChatMember>,
)

/** One AnyChat transcript row; memberKey == null marks a user row. */
data class AnyChatEntry(
    val id: String,
    val kind: ItemKind,
    val memberKey: String? = null,
    val memberName: String? = null,
    val gatewayLabel: String? = null,
    val text: String,
    val streaming: Boolean = false,
    val toolName: String? = null,
    val summary: String? = null,
    val durationS: Double? = null,
    // Use the same failure heuristic as the canonical chip; see ToolResult.
    val failed: Boolean = false,
    // Flattened tool output for non-verbose sessions.
    // (the wire omits result_text there) — see ToolResult.displayText.
    val outputText: String? = null,
)

data class AnyChatMemberState(
    val member: AnyChatMember,
    val displayName: String,
    val gatewayLabel: String?,
    /** True once the session opened (or failed with an inline error) — drives "Opening…". */
    val settled: Boolean,
)

/**
 * AnyChat: rooms + members persist in DataStore; transcripts live in memory for the
 * session (v1 limitation — full history stays in each bot's canonical chat). Each member
 * rides its OWN gateway connection through the same canonical-session machinery as
 * ChatViewModel (session.resume/create → prompt.submit → event stream → session.interrupt).
 * No relay involvement — sends are direct and instant.
 */
class AnyChatRepository(
    private val context: Context,
    private val gateways: GatewayManager,
    private val roster: RosterRepository,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val keyRooms = stringPreferencesKey("anychat_rooms_json")
    private val entrySeq = AtomicLong(0)

    private val _rooms = MutableStateFlow<List<AnyChatRoom>>(emptyList())
    val rooms: StateFlow<List<AnyChatRoom>> = _rooms

    private val _transcripts = MutableStateFlow<Map<String, List<AnyChatEntry>>>(emptyMap())
    val transcripts: StateFlow<Map<String, List<AnyChatEntry>>> = _transcripts

    /** roomId → set of member keys whose turn is currently streaming (idempotent tracking). */
    private val _streaming = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val streaming: StateFlow<Map<String, Set<String>>> = _streaming

    /** roomId → per-member open state for the "Opening chats…" moment. */
    private val _memberStates = MutableStateFlow<Map<String, List<AnyChatMemberState>>>(emptyMap())
    val memberStates: StateFlow<Map<String, List<AnyChatMemberState>>> = _memberStates

    private class MemberRuntime(
        val room: AnyChatRoom,
        val member: AnyChatMember,
        var displayName: String,
        var gatewayLabel: String?,
        var sessionId: String? = null,
        var lastSeq: Long = 0L,
        var eventJob: Job? = null,
        var reconnectJob: Job? = null,
        var openJob: Job? = null,
        var pinBlock: String? = null,
        var pinAlignment: AnyChatPinAlignmentState = AnyChatPinAlignmentState.PENDING,
        var completedTurnCount: Int = 0,
        val announcedPin: MutableStateFlow<Pair<String, String>> = MutableStateFlow("" to ""),
    )

    /** Room-scoped so the same bot in two rooms never shares a session runtime. */
    private val runtimes = mutableMapOf<String, MemberRuntime>()
    private val runtimeLock = Any()

    private fun runtimeKey(roomId: String, member: AnyChatMember) = "$roomId:${member.key}"

    init {
        scope.launch {
            val stored = context.anyChatStore.data.first()[keyRooms]
            _rooms.value = stored?.let {
                runCatching { json.decodeFromString<List<AnyChatRoom>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
        }
    }

    fun createRoom(name: String, members: List<AnyChatMember>): AnyChatRoom {
        val room = AnyChatRoom(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Any chat" },
            members = members.distinctBy { it.key },
        )
        _rooms.update { it + room }
        persistRooms()
        return room
    }

    fun deleteRoom(roomId: String) {
        closeRoomRuntimes(roomId)
        _rooms.update { rooms -> rooms.filterNot { it.id == roomId } }
        _transcripts.update { it - roomId }
        _streaming.update { it - roomId }
        _memberStates.update { it - roomId }
        persistRooms()
    }

    /** Opens every member's canonical session; idempotent, safe to call on every screen entry. */
    fun openRoom(roomId: String) {
        val room = _rooms.value.firstOrNull { it.id == roomId } ?: return
        room.members.forEach { member ->
            val rt = ensureRuntime(room, member)
            ensureOpening(rt)
        }
    }

    private fun ensureRuntime(room: AnyChatRoom, member: AnyChatMember): MemberRuntime =
        synchronized(runtimeLock) {
            runtimes[runtimeKey(room.id, member)]
                ?.takeIf { it.room.id == room.id }
                ?: MemberRuntime(
                    room = room,
                    member = member,
                    displayName = member.botName,
                    gatewayLabel = gateways.live.value[member.connectionId]?.record?.label,
                ).also { runtimes[runtimeKey(room.id, member)] = it }
        }

    private fun ensureOpening(rt: MemberRuntime) {
        synchronized(runtimeLock) {
            if (rt.openJob?.isActive == true) return
            val opening = rt.sessionId == null && rt.eventJob == null
            val recovering = rt.sessionId != null && rt.pinAlignment.shouldAttemptRecovery()
            if (opening || recovering) launchOpenAttempt(rt, recovering)
        }
    }

    private fun launchOpenAttempt(rt: MemberRuntime, recoverExisting: Boolean) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (recoverExisting) reopenMemberForAlignment(rt) else openMember(rt)
            } finally {
                synchronized(runtimeLock) {
                    if (rt.openJob === job) rt.openJob = null
                }
            }
        }
        rt.openJob = job
        job.start()
    }

    private suspend fun awaitAlignmentRecovery(rt: MemberRuntime): Boolean {
        if (rt.pinAlignment == AnyChatPinAlignmentState.READY) return true
        if (!rt.pinAlignment.shouldAttemptRecovery()) return false
        val job = synchronized(runtimeLock) {
            rt.openJob?.takeIf { it.isActive } ?: run {
                if (rt.sessionId == null) return@synchronized null
                launchOpenAttempt(rt, recoverExisting = true)
                rt.openJob
            }
        }
        job?.join()
        return rt.pinAlignment == AnyChatPinAlignmentState.READY
    }

    private suspend fun openMember(rt: MemberRuntime) {
        try {
            val row = withTimeout(15_000) {
                roster.roster
                    .first { rows -> rows.any { it.bot.connectionId == rt.member.connectionId && it.bot.name == rt.member.botName } }
                    .first { it.bot.connectionId == rt.member.connectionId && it.bot.name == rt.member.botName }
                    .bot
            }
            rt.displayName = row.displayName ?: row.name
            val live = gateways.live.value[rt.member.connectionId]
                ?: throw IllegalStateException("gateway is offline")
            rt.gatewayLabel = live.record.label
            val canonicalId = row.canonicalSessionId
            var opened: JsonObject? = null
            if (canonicalId != null) {
                opened = try {
                    // Server ≥0.21.1 resolves profile-scoped sessions only when the profile is named.
                    live.gateway.request(
                        Catalog.METHOD_SESSION_RESUME,
                        buildJsonObject { put("session_id", canonicalId); put("profile", rt.member.botName) },
                        120_000,
                    )
                } catch (_: Exception) {
                    null // canonical session gone — fall through to create
                }
            }
            val session = opened ?: live.gateway.request(
                Catalog.METHOD_SESSION_CREATE,
                CanonicalChat.createParams(rt.member.botName, row.model, row.provider),
                120_000,
            )
            adopt(rt, session)
            startCollectors(rt, live.gateway)
            alignMemberPin(rt, live.gateway, row, session)
        } catch (e: Exception) {
            val message = when (e) {
                is TimeoutCancellationException -> "${rt.displayName} didn't come online — check its gateway"
                else -> "${rt.displayName}: ${e.message ?: "couldn't open the chat"}"
            }
            appendError(rt, message)
        }
        setMemberState(rt) { it.copy(settled = true) }
    }

    private suspend fun reopenMemberForAlignment(rt: MemberRuntime) {
        val previousAlignment = rt.pinAlignment
        var createdAfterDeferred = false
        rt.pinAlignment = AnyChatPinAlignmentState.PENDING
        rt.pinBlock = "${rt.displayName}'s saved model alignment is being retried."
        try {
            val row = withTimeout(15_000) {
                roster.roster
                    .first { rows -> rows.any { it.bot.connectionId == rt.member.connectionId && it.bot.name == rt.member.botName } }
                    .first { it.bot.connectionId == rt.member.connectionId && it.bot.name == rt.member.botName }
                    .bot
            }
            rt.displayName = row.displayName ?: row.name
            val live = gateways.live.value[rt.member.connectionId]
                ?: throw IllegalStateException("gateway is offline")
            rt.gatewayLabel = live.record.label
            val canonicalId = row.canonicalSessionId ?: rt.sessionId
            val resumed = if (canonicalId == null) {
                null
            } else {
                try {
                    live.gateway.request(
                        Catalog.METHOD_SESSION_RESUME,
                        buildJsonObject { put("session_id", canonicalId); put("profile", rt.member.botName) },
                        120_000,
                    ).takeIf { (it["session_id"] as? JsonPrimitive)?.isString == true }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            rt.announcedPin.value = "" to ""
            val session = resumed ?: live.gateway.request(
                Catalog.METHOD_SESSION_CREATE,
                CanonicalChat.createParams(rt.member.botName, row.model, row.provider),
                120_000,
            ).also {
                createdAfterDeferred = previousAlignment == AnyChatPinAlignmentState.DEFERRED
            }
            check(
                (session["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true,
            ) { "session recovery returned no session id" }
            val created = resumed == null
            rt.pinAlignment = if (previousAlignment == AnyChatPinAlignmentState.DEFERRED && created) {
                AnyChatPinAlignmentState.BLOCKED
            } else {
                previousAlignment
            }
            adopt(rt, session)
            startCollectors(rt, live.gateway)
            if (previousAlignment != AnyChatPinAlignmentState.DEFERRED || created) {
                alignMemberPin(rt, live.gateway, row, session, forceConfigSet = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (previousAlignment == AnyChatPinAlignmentState.DEFERRED && !createdAfterDeferred) {
                setPinAlignmentDeferred(rt)
            } else {
                setPinAlignmentBlocked(
                    rt,
                    "${rt.displayName}'s saved model alignment could not be confirmed. Retry after checking its chat.",
                )
            }
        }
        setMemberState(rt) { it.copy(settled = true) }
    }

    private suspend fun alignMemberPin(
        rt: MemberRuntime,
        gw: HermesGateway,
        row: BotRow,
        opened: JsonObject,
        forceConfigSet: Boolean = false,
    ) {
        rt.pinAlignment = AnyChatPinAlignmentState.PENDING
        rt.pinBlock = "${rt.displayName}'s saved model alignment is still being checked."
        val savedModel = row.model?.trim().orEmpty()
        val savedProvider = row.provider?.trim().orEmpty()
        if (savedModel.isBlank() || savedProvider.isBlank()) {
            setPinAlignmentReady(rt)
            return
        }
        fun matches(pin: Pair<String, String>) =
            pin.first.isNotBlank() && !CanonicalChat.pinMismatch(pin.first, pin.second, savedModel, savedProvider)
        val sid = rt.sessionId ?: return
        val built = if (forceConfigSet) {
            null
        } else {
            withTimeoutOrNull(8_000) { rt.announcedPin.first { it.first.isNotBlank() } }
        }
        var confirmed = built?.takeIf { matches(it) }
        var last = CanonicalChat.AlignResult(false, null)
        if (confirmed == null) {
            val completedBeforeSwitch = rt.completedTurnCount
            last = alignPin(rt, sid, row, savedProvider, savedModel, gw)
            if (last.deferred) {
                setPinAlignmentDeferred(rt)
                if (rt.completedTurnCount > completedBeforeSwitch) {
                    setPinAlignmentReady(rt)
                }
                return
            }
            if (last.accepted) confirmed = savedModel to savedProvider
            else if (last.confirmationRequired) {
                setPinAlignmentBlocked(
                    rt,
                    "${rt.displayName}'s saved model requires confirmation. Open its chat to review the gateway warning.",
                )
                return
            } else {
                val completedBeforeRetry = rt.completedTurnCount
                last = alignPin(rt, sid, row, savedProvider, savedModel, gw)
                if (last.deferred) {
                    setPinAlignmentDeferred(rt)
                    if (rt.completedTurnCount > completedBeforeRetry) {
                        setPinAlignmentReady(rt)
                    }
                    return
                }
                if (last.accepted) confirmed = savedModel to savedProvider
            }
        }
        if (confirmed != null) {
            setPinAlignmentReady(rt)
        } else {
            val now = rt.announcedPin.value.let {
                if (it.first.isBlank()) CanonicalChat.runningPin(opened) else it
            }
            val why = last.detail?.let { " $it" }.orEmpty()
            setPinAlignmentBlocked(
                rt,
                "${rt.displayName} is on ${now.second.ifBlank { "unknown" }}/${now.first.ifBlank { "unknown" }}. " +
                    "The bot is set to $savedProvider/$savedModel.$why",
            )
        }
    }

    /** Returns null after a failed/ambiguous request, leaving prompt.submit fail-closed. */
    private suspend fun alignPin(
        rt: MemberRuntime,
        sid: String,
        row: BotRow,
        provider: String,
        model: String,
        gw: HermesGateway,
    ): CanonicalChat.AlignResult = try {
        CanonicalChat.alignSession(sid, row.name, provider, model) { params ->
            gw.request(Catalog.METHOD_CONFIG_SET, params, 60_000)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        setPinAlignmentBlocked(
            rt,
            "${rt.displayName}'s saved model alignment could not be confirmed. Retry after checking its chat.",
        )
        throw e
    }

    private fun setPinAlignmentReady(rt: MemberRuntime) {
        rt.pinAlignment = AnyChatPinAlignmentState.READY
        rt.pinBlock = null
    }

    private fun setPinAlignmentDeferred(rt: MemberRuntime) {
        rt.pinAlignment = AnyChatPinAlignmentState.DEFERRED
        rt.pinBlock = "${rt.displayName}'s saved model switch is queued for the next turn. Wait for its current turn to finish, then retry."
    }

    private fun setPinAlignmentBlocked(rt: MemberRuntime, reason: String) {
        rt.pinAlignment = AnyChatPinAlignmentState.BLOCKED
        rt.pinBlock = reason
    }

    private fun reconcileDeferredAlignment(rt: MemberRuntime, result: JsonObject) {
        val resumed = rt.pinAlignment.afterResume(
            running = boolField(result, "running"),
            inflight = boolField(result, "inflight"),
        )
        if (resumed == AnyChatPinAlignmentState.READY) setPinAlignmentReady(rt)
    }

    private fun adopt(rt: MemberRuntime, result: JsonObject) {
        rt.sessionId = (result["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val resumedPin = CanonicalChat.runningPin(result)
        if (resumedPin.first.isNotBlank() || resumedPin.second.isNotBlank()) {
            rt.announcedPin.value = resumedPin
        }
        reconcileDeferredAlignment(rt, result)
        val items = ChatMessagesParser.parse(result["messages"] as? kotlinx.serialization.json.JsonArray)
        val history = items.map { item ->
            val isUser = item.kind == ItemKind.USER
            AnyChatEntry(
                id = nextId(),
                kind = item.kind,
                memberKey = if (isUser) null else rt.member.key,
                memberName = if (isUser) null else rt.displayName,
                gatewayLabel = if (isUser) null else rt.gatewayLabel,
                text = item.text,
                toolName = item.toolName,
                summary = item.summary,
                durationS = item.durationS,
                failed = item.failed,
            )
        }
        replaceMemberEntries(rt.room.id, rt.member.key, history)
        val running = boolField(result, "running")
        val inflight = boolField(result, "inflight")
        if (running == true || inflight == true) {
            streamingAdd(rt.room.id, rt.member.key)
        } else if (running == false && inflight == false) {
            finalizeMemberStreaming(rt)
            streamingRemove(rt.room.id, rt.member.key)
        }
    }

    private fun startCollectors(rt: MemberRuntime, gw: HermesGateway) {
        val sid = rt.sessionId ?: return
        rt.eventJob?.cancel()
        rt.eventJob = scope.launch {
            gw.events.collect { ev ->
                if (ev.sessionId == sid) onMemberEvent(rt, ev)
            }
        }
        rt.reconnectJob?.cancel()
        rt.reconnectJob = scope.launch {
            var storedEpoch = (gw.state.value as? SocketState.Ready)?.replayEpoch
            var droppedSinceReady = false
            gw.state.collect { st ->
                when {
                    st is SocketState.Disconnected || st is SocketState.Idle -> droppedSinceReady = true
                    st is SocketState.Ready && droppedSinceReady -> {
                        val epochChanged = storedEpoch != null && st.replayEpoch != storedEpoch
                        storedEpoch = st.replayEpoch
                        droppedSinceReady = false
                        if (epochChanged) reloadMember(rt) else catchUpMember(rt, gw)
                    }
                }
            }
        }
    }

    private suspend fun catchUpMember(rt: MemberRuntime, gw: HermesGateway) {
        val sid = rt.sessionId ?: return
        try {
            val result = gw.since(sid, rt.lastSeq)
            if (result.truncated) {
                reloadMember(rt)
                return
            }
            result.events.forEach { onMemberEvent(rt, it) }
        } catch (_: Exception) {
            // best-effort; the next reconnect or a fresh send still works
        }
    }

    private suspend fun reloadMember(rt: MemberRuntime) {
        val gw = gateways.live.value[rt.member.connectionId]?.gateway ?: return
        val sid = rt.sessionId ?: return
        try {
            adopt(rt, gw.request(
                Catalog.METHOD_SESSION_RESUME,
                buildJsonObject { put("session_id", sid); put("profile", rt.member.botName) },
                120_000,
            ))
        } catch (_: Exception) {
        }
    }

    /** One member's stream events → member-tagged transcript rows (mirrors ChatViewModel). */
    private fun onMemberEvent(rt: MemberRuntime, ev: GatewayEvent) {
        ev.seq?.let { if (it > rt.lastSeq) rt.lastSeq = it }
        fun str(key: String): String? =
            (ev.payload[key] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        when (ev.type) {
            Catalog.EVENT_SESSION_INFO -> {
                val pin = CanonicalChat.runningPin(ev.payload)
                if (pin.first.isNotBlank() || pin.second.isNotBlank()) {
                    rt.announcedPin.value = pin
                }
            }
            Catalog.EVENT_MESSAGE_START -> {
                finalizeMemberStreaming(rt)
                append(rt.room.id, rt.assistantEntry("", streaming = true))
                streamingAdd(rt.room.id, rt.member.key)
            }
            Catalog.EVENT_MESSAGE_DELTA -> {
                val delta = str("text").orEmpty()
                if (delta.isNotEmpty()) appendDelta(rt, delta)
            }
            Catalog.EVENT_MESSAGE_INTERIM -> setMemberStreamingText(rt, str("text").orEmpty())
            Catalog.EVENT_MESSAGE_COMPLETE -> {
                rt.completedTurnCount++
                if (rt.pinAlignment == AnyChatPinAlignmentState.DEFERRED) {
                    setPinAlignmentReady(rt)
                }
                setMemberStreamingText(rt, str("text").orEmpty(), finalize = true)
                streamingRemove(rt.room.id, rt.member.key)
                val errText = str("error")
                val status = str("status")
                if (errText != null || status == "error") {
                    appendError(rt, errText ?: "${rt.displayName}'s turn failed")
                }
            }
            Catalog.EVENT_TOOL_START -> {
                val toolId = (ev.payload["tool_id"] as? JsonPrimitive)?.content ?: nextId()
                append(
                    rt.room.id,
                    AnyChatEntry(
                        id = "tool-${rt.member.key}-$toolId",
                        kind = ItemKind.TOOL,
                        memberKey = rt.member.key,
                        memberName = rt.displayName,
                        gatewayLabel = rt.gatewayLabel,
                        text = (str("args_text")
                            ?: ToolResult.commandFromArgs(str("name"), ev.payload["args"])).orEmpty(),
                        streaming = true,
                        toolName = str("name") ?: "tool",
                    ),
                )
            }
            Catalog.EVENT_TOOL_COMPLETE -> {
                val toolId = (ev.payload["tool_id"] as? JsonPrimitive)?.content
                updateEntries(rt.room.id) { items ->
                    val idx = toolId?.let { id -> items.indexOfFirst { it.id == "tool-${rt.member.key}-$id" } } ?: -1
                    if (idx < 0) {
                        items
                    } else {
                        items.toMutableList().also {
                            it[idx] = it[idx].copy(
                                streaming = false,
                                summary = str("summary"),
                                durationS = (ev.payload["duration_s"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                                // Mirror the desktop's failure heuristic.
                                failed = ToolResult.isFailure(it[idx].toolName, ev.payload["result"]),
                                // result_text is verbose-only on the wire.
                                outputText = ToolResult.displayText(ev.payload["result"]),
                            )
                        }
                    }
                }
            }
            Catalog.EVENT_APPROVAL_REQUEST,
            Catalog.EVENT_CLARIFY_REQUEST,
            Catalog.EVENT_SUDO_REQUEST,
            Catalog.EVENT_SECRET_REQUEST,
            -> appendError(
                rt,
                "${rt.displayName} needs your approval — open ${rt.displayName}'s chat to respond.",
            )
            Catalog.EVENT_ERROR -> {
                rt.completedTurnCount++
                if (rt.pinAlignment == AnyChatPinAlignmentState.DEFERRED) {
                    setPinAlignmentReady(rt)
                }
                appendError(rt, str("message") ?: "${rt.displayName} hit an error")
                streamingRemove(rt.room.id, rt.member.key)
            }
        }
    }

    /**
     * Fans the user text out to every member session in parallel (direct, no relay). A
     * member whose canonical session id went stale (gateway restart re-mints ids) gets ONE
     * re-resolve + resubmit; any member that can't be reached surfaces a humane error —
     * never a silent drop.
     */
    fun send(roomId: String, rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val room = _rooms.value.firstOrNull { it.id == roomId } ?: return
        append(
            roomId,
            AnyChatEntry(id = nextId(), kind = ItemKind.USER, text = text),
        )
        scope.launch {
            room.members.forEach { member ->
                launch { sendToMember(room, member, text) }
            }
        }
    }

    private suspend fun sendToMember(room: AnyChatRoom, member: AnyChatMember, text: String) {
        val rt = ensureRuntime(room, member)
        if (rt.sessionId != null && rt.pinAlignment.shouldAttemptRecovery()) {
            if (!awaitAlignmentRecovery(rt)) {
                appendError(rt, alignmentBlockReason(rt))
                return
            }
        }
        if (rt.sessionId != null && rt.pinAlignment != AnyChatPinAlignmentState.READY) {
            appendError(rt, alignmentBlockReason(rt))
            return
        }
        AnyChatMemberSend(member, memberOps(rt)).send(
            text = text,
            currentSessionId = rt.sessionId,
            openIfMissing = { ensureOpening(rt) },
        )
    }

    /** [AnyChatMemberSend.Ops] over this member's real gateway/roster/transcript. */
    private fun memberOps(rt: MemberRuntime) = object : AnyChatMemberSend.Ops {
        private var alignmentBeforeResolve: AnyChatPinAlignmentState? = null
        private var adoptedSession: JsonObject? = null

        override val displayName: String get() = rt.displayName

        override fun gatewayLive(): Boolean = gateways.live.value[rt.member.connectionId] != null

        override fun alignedForSubmit(sessionId: String): Boolean =
            rt.sessionId == sessionId && rt.pinAlignment == AnyChatPinAlignmentState.READY

        override fun alignmentBlockReason(): String = alignmentBlockReason(rt)

        override suspend fun submit(sessionId: String, text: String) {
            val gw = gateways.live.value[rt.member.connectionId]?.gateway
                ?: throw IllegalStateException("gateway is offline")
            gw.request(Catalog.METHOD_PROMPT_SUBMIT, CanonicalChat.submitParams(sessionId, text), 30_000)
        }

        override suspend fun resume(sessionId: String): String? = runCatching {
            beginSessionResolve()
            val gw = gateways.live.value[rt.member.connectionId]?.gateway ?: return null
            val result = gw.request(
                Catalog.METHOD_SESSION_RESUME,
                buildJsonObject { put("session_id", sessionId); put("profile", rt.member.botName) },
                120_000,
            )
            adoptedSession = result
            (result["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: sessionId
        }.getOrNull()

        override suspend fun createSession(profile: String): String? = runCatching {
            beginSessionResolve()
            val gw = gateways.live.value[rt.member.connectionId]?.gateway ?: return null
            val row = roster.roster.value.firstOrNull {
                it.bot.connectionId == rt.member.connectionId && it.bot.name == profile
            }?.bot
            gw.request(
                Catalog.METHOD_SESSION_CREATE,
                CanonicalChat.createParams(profile, row?.model, row?.provider),
                120_000,
            )
                .let { result ->
                    adoptedSession = result
                    (result["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
        }.getOrNull()

        override suspend fun currentRow(staleSessionId: String?): BotRow? {
            beginSessionResolve()
            fun match(rows: List<RosterEntry>): BotRow? =
                rows.firstOrNull {
                    it.bot.connectionId == rt.member.connectionId && it.bot.name == rt.member.botName
                }?.bot

            // Wait (≤8 s) for the 5 s roster poll / sessions.changed wake to surface a
            // canonical id that differs from the stale one; then settle for what's there.
            val fresh = withTimeoutOrNull(8_000) {
                roster.roster.first { rows -> match(rows)?.canonicalSessionId != staleSessionId }
            }
            val row = fresh?.let { match(it) } ?: match(roster.roster.value) ?: return null
            row.displayName?.takeIf { it.isNotBlank() }?.let { rt.displayName = it }
            return row
        }

        override suspend fun sessionAdopted(sessionId: String): Boolean {
            val gw = gateways.live.value[rt.member.connectionId]?.gateway ?: return false
            val result = adoptedSession ?: return false
            val previousAlignment = alignmentBeforeResolve ?: AnyChatPinAlignmentState.PENDING
            rt.sessionId = sessionId
            rt.lastSeq = 0L // fresh session → fresh event seq watermark
            val pin = CanonicalChat.runningPin(result)
            rt.announcedPin.setAdoptedSessionPin(pin)
            rt.pinAlignment = previousAlignment
            reconcileDeferredAlignment(rt, result)
            startCollectors(rt, gw)
            val running = boolField(result, "running")
            val inflight = boolField(result, "inflight")
            if (running == true || inflight == true) {
                streamingAdd(rt.room.id, rt.member.key)
            } else if (running == false && inflight == false) {
                finalizeMemberStreaming(rt)
                streamingRemove(rt.room.id, rt.member.key)
            }
            if (previousAlignment == AnyChatPinAlignmentState.DEFERRED &&
                rt.pinAlignment == AnyChatPinAlignmentState.DEFERRED
            ) {
                return false
            }
            val row = roster.roster.value.firstOrNull {
                it.bot.connectionId == rt.member.connectionId && it.bot.name == rt.member.botName
            }?.bot
            if (row == null) {
                setPinAlignmentBlocked(rt, "${rt.displayName}'s saved model could not be checked after session recovery.")
                return false
            }
            try {
                alignMemberPin(rt, gw, row, result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            adoptedSession = null
            alignmentBeforeResolve = null
            return rt.pinAlignment == AnyChatPinAlignmentState.READY
        }

        private fun beginSessionResolve() {
            if (alignmentBeforeResolve == null) alignmentBeforeResolve = rt.pinAlignment
            rt.pinAlignment = AnyChatPinAlignmentState.PENDING
            rt.pinBlock = "${rt.displayName}'s saved model alignment is being checked after session recovery."
        }

        override fun streamingAborted() {
            finalizeMemberStreaming(rt)
            streamingRemove(rt.room.id, rt.member.key)
        }

        override fun error(message: String) = appendError(rt, message)
    }

    private fun alignmentBlockReason(rt: MemberRuntime): String = rt.pinBlock ?: when (rt.pinAlignment) {
        AnyChatPinAlignmentState.PENDING -> "${rt.displayName}'s saved model alignment is still being checked."
        AnyChatPinAlignmentState.DEFERRED -> "${rt.displayName}'s saved model switch is queued until its current turn finishes."
        AnyChatPinAlignmentState.BLOCKED -> "${rt.displayName}'s saved model alignment could not be confirmed."
        AnyChatPinAlignmentState.READY -> "${rt.displayName}'s saved model alignment needs to be checked again."
    }

    /** Stop = per-session interrupt across every member. */
    fun interrupt(roomId: String) {
        val room = _rooms.value.firstOrNull { it.id == roomId } ?: return
        val targets = readyRuntimes(room)
        scope.launch {
            targets.map { rt ->
                async {
                    gateways.live.value[rt.member.connectionId]?.gateway?.let { gw ->
                        runCatching {
                            gw.request(
                                Catalog.METHOD_SESSION_INTERRUPT,
                                buildJsonObject { put("session_id", rt.sessionId) },
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        _streaming.update { it + (roomId to emptySet()) }
    }

    fun steer(roomId: String, rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val room = _rooms.value.firstOrNull { it.id == roomId } ?: return
        val targets = readyRuntimes(room)
        scope.launch {
            targets.map { rt ->
                async {
                    gateways.live.value[rt.member.connectionId]?.gateway?.let { gw ->
                        runCatching {
                            gw.request(
                                Catalog.METHOD_SESSION_STEER,
                                buildJsonObject { put("session_id", rt.sessionId); put("text", text) },
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // --- transcript helpers ---------------------------------------------------------------

    /** Room-scoped, session-ready runtimes (best-effort interrupt/steer targets). */
    private fun readyRuntimes(room: AnyChatRoom): List<MemberRuntime> =
        synchronized(runtimeLock) {
            room.members.mapNotNull { runtimes[runtimeKey(room.id, it)] }.filter { it.sessionId != null }
        }

    private fun nextId(): String = "e${entrySeq.incrementAndGet()}"

    private fun MemberRuntime.assistantEntry(text: String, streaming: Boolean) = AnyChatEntry(
        id = nextId(),
        kind = ItemKind.ASSISTANT,
        memberKey = member.key,
        memberName = displayName,
        gatewayLabel = gatewayLabel,
        text = text,
        streaming = streaming,
    )

    private fun append(roomId: String, entry: AnyChatEntry) =
        updateEntries(roomId) { it + entry }

    private fun appendError(rt: MemberRuntime, text: String) =
        append(
            rt.room.id,
            AnyChatEntry(
                id = nextId(),
                kind = ItemKind.ERROR,
                memberKey = rt.member.key,
                memberName = rt.displayName,
                gatewayLabel = rt.gatewayLabel,
                text = text,
            ),
        )

    private fun appendDelta(rt: MemberRuntime, delta: String) {
        updateEntries(rt.room.id) { items ->
            val idx = items.indexOfLast { it.streaming && it.kind == ItemKind.ASSISTANT && it.memberKey == rt.member.key }
            if (idx < 0) {
                items + rt.assistantEntry(delta, streaming = true)
            } else {
                items.toMutableList().also { list ->
                    val cur = list[idx]
                    list[idx] = cur.copy(text = cur.text + delta)
                }
            }
        }
    }

    private fun setMemberStreamingText(rt: MemberRuntime, value: String, finalize: Boolean = false) {
        updateEntries(rt.room.id) { items ->
            val idx = items.indexOfLast { it.streaming && it.kind == ItemKind.ASSISTANT && it.memberKey == rt.member.key }
            when {
                idx < 0 && finalize && value.isEmpty() -> items
                idx < 0 -> items + rt.assistantEntry(value, streaming = !finalize)
                else -> items.toMutableList().also {
                    val cur = it[idx]
                    it[idx] = cur.copy(text = value, streaming = !finalize)
                }
            }
        }
    }

    private fun finalizeMemberStreaming(rt: MemberRuntime) {
        updateEntries(rt.room.id) { items ->
            items.map { item ->
                if (item.streaming && item.kind == ItemKind.ASSISTANT && item.memberKey == rt.member.key) {
                    item.copy(streaming = false)
                } else {
                    item
                }
            }
        }
    }

    private fun replaceMemberEntries(roomId: String, memberKey: String, history: List<AnyChatEntry>) {
        updateEntries(roomId) { items ->
            // Generated rows carry "e…"/"tool-…" ids; parsed history rows are re-appended.
            items.filterNot { it.memberKey == memberKey && (it.id.startsWith("e") || it.id.startsWith("tool-")) } + history
        }
    }

    private fun updateEntries(roomId: String, block: (List<AnyChatEntry>) -> List<AnyChatEntry>) {
        _transcripts.update { cur -> cur + (roomId to block(cur[roomId].orEmpty())) }
    }

    private fun streamingAdd(roomId: String, memberKey: String) {
        _streaming.update { cur -> cur + (roomId to ((cur[roomId] ?: emptySet()) + memberKey)) }
    }

    private fun streamingRemove(roomId: String, memberKey: String) {
        _streaming.update { cur -> cur + (roomId to ((cur[roomId] ?: emptySet()) - memberKey)) }
    }

    private fun setMemberState(rt: MemberRuntime, transform: (AnyChatMemberState) -> AnyChatMemberState) {
        _memberStates.update { cur ->
            val list = cur[rt.room.id].orEmpty()
            val base = list.firstOrNull { it.member.key == rt.member.key }
                ?: AnyChatMemberState(
                    member = rt.member,
                    displayName = rt.displayName,
                    gatewayLabel = rt.gatewayLabel,
                    settled = false,
                )
            val next = transform(base)
            cur + (rt.room.id to (list.filterNot { it.member.key == rt.member.key } + next))
        }
    }

    private fun boolField(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun closeRoomRuntimes(roomId: String) {
        synchronized(runtimeLock) {
            runtimes.values.filter { it.room.id == roomId }.forEach { rt ->
                rt.openJob?.cancel()
                rt.eventJob?.cancel()
                rt.reconnectJob?.cancel()
                runtimes.remove(runtimeKey(roomId, rt.member))
            }
        }
    }

    private fun persistRooms() {
        val snapshot = _rooms.value
        scope.launch {
            context.anyChatStore.edit { prefs ->
                prefs[keyRooms] = json.encodeToString(snapshot)
            }
        }
    }
}
