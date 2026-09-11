package ai.hermes.bots.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ItemKind { USER, ASSISTANT, TOOL, ERROR }

data class ChatItem(
    val id: String,
    val kind: ItemKind,
    val text: String,
    val streaming: Boolean = false,
    val toolName: String? = null,
    val summary: String? = null,
    val durationS: Double? = null,
)

data class ApprovalCard(
    val requestId: String,
    val kind: String,
    val command: String?,
    val choices: List<String>,
)

/** An image queued to ride with the user's next message (image.attach_bytes). */
data class PendingImage(
    val filename: String,
    val base64: String,
)

data class ChatUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<ChatItem> = emptyList(),
    val approval: ApprovalCard? = null,
    val streaming: Boolean = false,
    val sessionTitle: String? = null,
    val statusText: String? = null,
    val approvalResolved: String? = null,
    val approvalExpired: Boolean = false,
    val botModel: String? = null,
    val pendingImage: PendingImage? = null,
)

/**
 * B4 same-name disambiguation: bot names that exist on more than one connection across the
 * union roster — wherever such a name renders, show the owning connection's label.
 */
object BotNameCollisions {
    fun compute(rows: List<ai.hermes.bots.data.BotRow>): Set<String> =
        rows.groupBy { it.name }
            .filterValues { bots -> bots.map { b -> b.connectionId }.distinct().size > 1 }
            .keys
}

/** Parses the display `messages` array returned by session.resume / session.create
 *  (server shape: {"role": "user"|"assistant"|"tool", "text"|...}; see PROTOCOL.md §5.2). */
object ChatMessagesParser {
    fun parse(messages: JsonArray?): List<ChatItem> =
        messages?.mapIndexedNotNull { i, el -> parseOne(el, i) }.orEmpty()

    private fun parseOne(element: kotlinx.serialization.json.JsonElement, index: Int): ChatItem? {
        val m = element as? JsonObject ?: return null
        val role = (m["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return when (role) {
            "user" -> ChatItem("h-$index", ItemKind.USER, messageText(m))
            "assistant" -> ChatItem("h-$index", ItemKind.ASSISTANT, messageText(m))
            "tool" -> ChatItem(
                id = "h-$index",
                kind = ItemKind.TOOL,
                text = messageText(m),
                toolName = stringField(m, "name") ?: "tool",
                summary = stringField(m, "context"),
            )
            else -> null
        }
    }

    private fun messageText(m: JsonObject): String =
        (m["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    private fun stringField(m: JsonObject, key: String): String? =
        (m[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
