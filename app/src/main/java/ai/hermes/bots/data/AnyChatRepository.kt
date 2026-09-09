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

/** Client-side room over N per-bot canonical sessions (FLEET-CONNECT-SPEC B2). */
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
)

data class AnyChatMemberState(
    val member: AnyChatMember,
    val displayName: String,
    val gatewayLabel: String?,
    /** True once the session opened (or failed with an inline error) — drives "Opening…". */
    val settled: Boolean,
)

/**
 * AnyChat (B2): rooms + members persist in DataStore; transcripts live in memory for the
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
        if (rt.sessionId == null && rt.eventJob == null) {
            scope.launch { openMember(rt) }
        }
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
            var adopted = false
            if (canonicalId != null) {
                try {
                    adopt(rt, live.gateway.request(Catalog.METHOD_SESSION_RESUME, buildJsonObject { put("session_id", canonicalId) }, 120_000))
                    adopted = true
                } catch (_: Exception) {
                    // canonical session gone — fall through to create
                }
            }
            if (!adopted) {
                adopt(rt, live.gateway.request(Catalog.METHOD_SESSION_CREATE, CanonicalChat.createParams(rt.member.botName), 120_000))
            }
            startCollectors(rt, live.gateway)
        } catch (e: Exception) {
            val message = when (e) {
                is TimeoutCancellationException -> "${rt.displayName} didn't come online — check its gateway"
                else -> "${rt.displayName}: ${e.message ?: "couldn't open the chat"}"
            }
            appendError(rt, message)
        }
        setMemberState(rt) { it.copy(settled = true) }
    }

    private fun adopt(rt: MemberRuntime, result: JsonObject) {
        rt.sessionId = (result["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
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
            )
        }
        replaceMemberEntries(rt.room.id, rt.member.key, history)
        if (boolField(result, "running") == true || boolField(result, "inflight") == true) {
            streamingAdd(rt.room.id, rt.member.key)
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
            adopt(rt, gw.request(Catalog.METHOD_SESSION_RESUME, buildJsonObject { put("session_id", sid) }, 120_000))
        } catch (_: Exception) {
        }
    }

    /** One member's stream events → member-tagged transcript rows (mirrors ChatViewModel). */
    private fun onMemberEvent(rt: MemberRuntime, ev: GatewayEvent) {
        ev.seq?.let { if (it > rt.lastSeq) rt.lastSeq = it }
        fun str(key: String): String? =
            (ev.payload[key] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        when (ev.type) {
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
                        text = str("args_text").orEmpty(),
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
        AnyChatMemberSend(member, memberOps(rt)).send(
            text = text,
            currentSessionId = rt.sessionId,
            openIfMissing = { ensureOpening(rt) },
        )
    }

    /** [AnyChatMemberSend.Ops] over this member's real gateway/roster/transcript. */
    private fun memberOps(rt: MemberRuntime) = object : AnyChatMemberSend.Ops {
        override val displayName: String get() = rt.displayName

        override fun gatewayLive(): Boolean = gateways.live.value[rt.member.connectionId] != null

        override suspend fun submit(sessionId: String, text: String) {
            val gw = gateways.live.value[rt.member.connectionId]?.gateway
                ?: throw IllegalStateException("gateway is offline")
            gw.request(Catalog.METHOD_PROMPT_SUBMIT, CanonicalChat.submitParams(sessionId, text), 30_000)
        }

        override suspend fun resume(sessionId: String): String? = runCatching {
            val gw = gateways.live.value[rt.member.connectionId]?.gateway ?: return null
            val result = gw.request(
                Catalog.METHOD_SESSION_RESUME,
                buildJsonObject { put("session_id", sessionId) },
                120_000,
            )
            (result["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: sessionId
        }.getOrNull()

        override suspend fun createSession(profile: String): String? = runCatching {
            val gw = gateways.live.value[rt.member.connectionId]?.gateway ?: return null
            gw.request(Catalog.METHOD_SESSION_CREATE, CanonicalChat.createParams(profile), 120_000)
                .let { result ->
                    (result["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
        }.getOrNull()

        override suspend fun currentRow(staleSessionId: String?): BotRow? {
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

        override fun sessionAdopted(sessionId: String) {
            val gw = gateways.live.value[rt.member.connectionId]?.gateway ?: return
            rt.sessionId = sessionId
            rt.lastSeq = 0L // fresh session → fresh event seq watermark
            startCollectors(rt, gw)
        }

        override fun streamingAborted() {
            finalizeMemberStreaming(rt)
            streamingRemove(rt.room.id, rt.member.key)
        }

        override fun error(message: String) = appendError(rt, message)
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
