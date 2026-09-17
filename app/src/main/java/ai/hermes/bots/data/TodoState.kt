package ai.hermes.bots.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class TodoStatus { PENDING, ACTIVE, DONE }

data class TodoItem(val content: String, val status: TodoStatus)

/**
 * `todo.updated` (PROTOCOL.md §6) documents only "normalized todo state" — no payload shape —
 * so this parser is tolerant by contract (agent-ux-p0-spec.md §5): a top-level array or an
 * object wrapping one under `todos`/`items`/`list`; items are read by their common content
 * and status key aliases, unknown status strings fall back to pending. Anything that does not
 * parse returns empty, which renders nothing — a server variation can never break the chat.
 * Also feeds `session.resume`'s documented `todo_state?` (§5.2) so a reconnect mid-plan
 * restores the checklist.
 */
object TodoState {

    /** The pinned card must never become a wall — plans longer than this render truncated. */
    const val MAX_ITEMS = 20

    fun parse(payload: JsonElement?): List<TodoItem> {
        val array = when (payload) {
            null, is JsonNull -> return emptyList()
            is JsonArray -> payload
            is JsonObject -> (payload["todos"] ?: payload["items"] ?: payload["list"]) as? JsonArray
            else -> null
        } ?: return emptyList()
        return array.asSequence()
            .mapNotNull { item(it) }
            .take(MAX_ITEMS)
            .toList()
    }

    private fun item(el: JsonElement): TodoItem? {
        val obj = el as? JsonObject ?: return null
        val content = CONTENT_KEYS.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content.isNotBlank() }?.content
        } ?: return null
        val statusRaw = STATUS_KEYS.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        }?.lowercase()
        val status = when (statusRaw) {
            "completed", "complete", "done", "finished", "success" -> TodoStatus.DONE
            "in_progress", "inprogress", "active", "running", "current", "started" -> TodoStatus.ACTIVE
            else -> TodoStatus.PENDING
        }
        return TodoItem(content, status)
    }

    private val CONTENT_KEYS = listOf("content", "text", "title", "label", "task", "description")
    private val STATUS_KEYS = listOf("status", "state")
}
