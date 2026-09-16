package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.ProtocolException
import ai.hermes.bots.protocol.RpcException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class GroupCaps(val supported: Boolean, val protocolVersion: String, val driverReady: Boolean)

data class GroupRoom(
    val connectionId: String,
    val roomId: String,
    val name: String,
    val members: List<String>,
    val disbanded: Boolean,
    // Q10 (QA 2026-09-14): profile id → display name, off the same wire members array
    // (server stores display_name at create — tui_gateway/hosted_room_service.py:446).
    val memberNames: Map<String, String> = emptyMap(),
)

data class GroupLogEntry(
    val eventId: String?,
    val kind: String,
    val actor: String?,
    val text: String,
    val raw: JsonObject,
)

/**
 * One action waiting on the user, from `groups.state` → `driver_status.pending_actions`
 * (`tui_gateway/hosted_room_service.py:532-548`, shape `hosted_room_driver.py:392-405`).
 *
 * Q10 (QA 2026-09-14): `choices` mirrors the payload's `approval.choices` filtered to
 * {once, deny} exactly like the room driver does (hosted_room_driver.py:398-399, default
 * ["once","deny"] when empty) — the server REJECTS any other value
 * (`hosted_room_service.py` approve_room_task: "room approval choice must be once or deny").
 */
data class GroupPendingAction(
    val kind: String,
    val taskId: String,
    val memberId: String?,
    val executionGeneration: Long,
    val requestId: String?,
    val command: String?,
    val choices: List<String> = listOf("once", "deny"),
)

data class GroupRoomState(
    val room: GroupRoom?,
    val log: List<GroupLogEntry>,
    val pending: List<GroupPendingAction>,
)

private fun str(obj: JsonObject?, key: String): String? =
    (obj?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

/** Hosted-room group chats (PROTOCOL.md §5.6). On-demand calls; rooms cache refreshed by the UI. */
class GroupRepository(private val manager: GatewayManager) {

    private val _rooms = MutableStateFlow<Map<String, List<GroupRoom>>>(emptyMap())
    val rooms: StateFlow<Map<String, List<GroupRoom>>> = _rooms

    private suspend fun gateway(connectionId: String): HermesGateway =
        manager.live.first()[connectionId]?.gateway
            ?: throw IllegalStateException("connection $connectionId is not live")

    suspend fun capabilities(connectionId: String): GroupCaps =
        try {
            val r = gateway(connectionId).request(Catalog.METHOD_GROUPS_CAPABILITIES, JsonObject(emptyMap()))
            GroupCaps(
                supported = true,
                protocolVersion = str(r, "protocol_version") ?: "?",
                driverReady = (r["driver"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            )
        } catch (e: RpcException) {
            if (e.isMethodNotFound()) GroupCaps(supported = false, protocolVersion = "?", driverReady = false) else throw e
        }

    suspend fun refreshRooms(connectionId: String) {
        val result = gateway(connectionId).request(
            Catalog.METHOD_GROUPS_LIST,
            buildJsonObject { put("limit", 100) },
        )
        val rooms = (result["rooms"] as? JsonArray)?.mapNotNull { parseRoomPublic(connectionId, it) }.orEmpty()
        _rooms.update { it + (connectionId to rooms) }
    }

    suspend fun createRoom(connectionId: String, name: String, members: List<RoomMember>): GroupRoom {
        val result = gateway(connectionId).request(
            Catalog.METHOD_GROUPS_CREATE,
            buildJsonObject {
                // Server (v0.21.0) rejects a missing room_id with 4110 ("room_id must be a string"),
                // so we generate one client-side like the desktop does; create is idempotent on it.
                put("room_id", UUID.randomUUID().toString())
                put("name", name)
                put("members", JsonArray(members.map { it.toParams() }))
            },
            120_000,
        )
        val room = (result["room"] as? JsonObject)?.let { parseRoomPublic(connectionId, it) }
            ?: throw ai.hermes.bots.protocol.ProtocolException("groups.create returned no room")
        refreshRooms(connectionId)
        return room
    }

    suspend fun roomState(connectionId: String, roomId: String): GroupRoomState {
        val result = gateway(connectionId).request(
            Catalog.METHOD_GROUPS_STATE,
            buildJsonObject { put("room_id", roomId) },
        )
        val room = (result["room"] as? JsonObject)?.let { parseRoomPublic(connectionId, it) }
        // groups.state carries no transcript — the room log lives behind groups.log
        // (methods_groups.py:489 passthrough to gateway.hosted_rooms.read_events).
        val logResult = gateway(connectionId).request(
            Catalog.METHOD_GROUPS_LOG,
            buildJsonObject {
                put("room_id", roomId)
                put("since_seq", 0)
                put("limit", 100)
            },
        )
        val log = (logResult["events"] as? JsonArray)
            ?.mapNotNull { parseLogEntryPublic(it) }
            .orEmpty()
        val pending = (
            (result["driver_status"] as? JsonObject)?.get("pending_actions") as? JsonArray
            )
            ?.mapNotNull { parsePendingAction(it) }
            .orEmpty()
        return GroupRoomState(room, log, pending)
    }

    suspend fun sendUserMessage(connectionId: String, roomId: String, text: String): Boolean {
        gateway(connectionId).request(
            Catalog.METHOD_GROUPS_SEND,
            buildJsonObject {
                put("room_id", roomId)
                put("event_id", UUID.randomUUID().toString())
                put(
                    "payload",
                    buildJsonObject {
                        // Server (v0.21.0) validates user payloads with EXACT fields {text, thread_id}
                        // (gateway/hosted_room_discussion.py:49) — a "type" key fails with 5112.
                        put("text", text)
                        put("thread_id", "main")
                    },
                )
            },
            60_000,
        )
        return true
    }

    suspend fun stop(connectionId: String, roomId: String): Int {
        val r = gateway(connectionId).request(
            Catalog.METHOD_GROUPS_STOP,
            buildJsonObject { put("room_id", roomId); put("cancel_id", "android-stop") },
        )
        return (r["cancelled"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    }

    suspend fun disband(connectionId: String, roomId: String) {
        gateway(connectionId).request(
            Catalog.METHOD_GROUPS_DISBAND,
            buildJsonObject { put("room_id", roomId); put("cancel_id", "android-disband") },
            60_000,
        )
        refreshRooms(connectionId)
    }

    suspend fun approve(
        connectionId: String, roomId: String, memberId: String, taskId: String,
        executionGeneration: Long, choice: String, requestId: String,
    ) {
        gateway(connectionId).request(
            Catalog.METHOD_GROUPS_APPROVE,
            buildJsonObject {
                put("room_id", roomId)
                put("member_id", memberId)
                put("task_id", taskId)
                put("execution_generation", executionGeneration)
                put("choice", choice)
                put("request_id", requestId)
            },
        )
    }

    /** Resolve a pending approval — `choice` is "once" or "deny" (methods_groups.py:439-448). */
    suspend fun resolvePending(
        connectionId: String, roomId: String, action: GroupPendingAction, choice: String,
    ) {
        val memberId = action.memberId ?: throw ProtocolException("approval has no member")
        val requestId = action.requestId ?: throw ProtocolException("approval has no request id")
        gateway(connectionId).request(
            Catalog.METHOD_GROUPS_APPROVE,
            buildJsonObject {
                put("room_id", roomId)
                put("member_id", memberId)
                put("task_id", action.taskId)
                put("execution_generation", action.executionGeneration)
                put("choice", choice)
                put("request_id", requestId)
            },
        )
    }

    /** Retry one indeterminate room task after the user confirms (methods_groups.py:450-461). */
    suspend fun retryPending(connectionId: String, roomId: String, action: GroupPendingAction) {
        gateway(connectionId).request(
            Catalog.METHOD_GROUPS_RETRY,
            buildJsonObject {
                put("room_id", roomId)
                put("task_id", action.taskId)
            },
            60_000,
        )
    }

    data class RoomMember(val profile: String, val displayName: String) {
        fun toParams(): JsonObject = buildJsonObject {
            put("member_id", profile)
            put("profile", profile)
            put("handle", profile.lowercase().replace(' ', '-'))
            put("display_name", displayName)
        }
    }

    companion object {
        fun parseRoomForTest(connectionId: String, element: JsonElement): GroupRoom? = parseRoomPublic(connectionId, element)
        fun parseLogEntryForTest(element: JsonElement): GroupLogEntry? = parseLogEntryPublic(element)
        fun parsePendingActionForTest(element: JsonElement): GroupPendingAction? = parsePendingAction(element)

        internal fun parsePendingAction(element: JsonElement): GroupPendingAction? {
            val o = element as? JsonObject ?: return null
            val kind = str(o, "kind") ?: return null
            val taskId = str(o, "task_id") ?: return null
            val approval = o["approval"] as? JsonObject
            // Q10: mirror the driver's own sanitize — keep only once/deny, default both.
            val wireChoices = (approval?.get("choices") as? JsonArray)
                ?.mapNotNull { c -> (c as? JsonPrimitive)?.takeIf { it.isString }?.content }
                ?.filter { it in setOf("once", "deny") }
                .orEmpty()
            return GroupPendingAction(
                kind = kind,
                taskId = taskId,
                memberId = str(o, "member_id"),
                executionGeneration = (o["execution_generation"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                requestId = str(o, "request_id") ?: str(approval, "request_id"),
                command = str(approval, "command"),
                choices = wireChoices.ifEmpty { listOf("once", "deny") },
            )
        }

        internal fun parseRoomPublic(connectionId: String, element: JsonElement): GroupRoom? {
            val o = element as? JsonObject ?: return null
            val roomId = str(o, "room_id") ?: str(o, "id") ?: return null
            // Q10: collect display_name per profile off the same members array.
            val rawMembers = (o["members"] as? JsonArray).orEmpty()
            val names = rawMembers
                .filterIsInstance<JsonObject>()
                .mapNotNull { m ->
                    val profile = str(m, "profile") ?: str(m, "member_id") ?: return@mapNotNull null
                    val name = str(m, "display_name") ?: return@mapNotNull null
                    profile to name
                }
                .toMap()
            return GroupRoom(
                connectionId = connectionId,
                roomId = roomId,
                name = str(o, "name").orEmpty().ifBlank { roomId },
                members = rawMembers.mapNotNull { m ->
                    when (m) {
                        is JsonPrimitive -> if (m.isString) m.content else null
                        is JsonObject -> str(m, "profile") ?: str(m, "member_id")
                        else -> null
                    }
                },
                disbanded = (o["disbanded_at"] as? JsonPrimitive)?.let { it !is JsonNull } == true,
                memberNames = names,
            )
        }

        internal fun parseLogEntryPublic(element: JsonElement): GroupLogEntry? {
            val o = element as? JsonObject ?: return null
            val kind = str(o, "type") ?: str(o, "kind") ?: return null
            val actor = when (val a = o["actor"]) {
                is JsonObject -> str(a, "display_name") ?: str(a, "profile")
                    ?: str(a, "kind") ?: str(a, "id")
                is JsonPrimitive -> if (a.isString) a.content else null
                else -> null
            }
            val payload = o["payload"] as? JsonObject
            val text = (payload?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return GroupLogEntry(
                eventId = str(o, "event_id") ?: str(o, "id"),
                kind = kind,
                actor = actor,
                text = text,
                raw = o,
            )
        }
    }
}
