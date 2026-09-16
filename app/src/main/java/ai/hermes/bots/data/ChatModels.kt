package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
    // Q12 (QA 2026-09-14): set from the tool.complete payload — see ToolResult.
    val failed: Boolean = false,
    // Q7 follow-up (live QA 2026-09-14): flattened tool output for non-verbose sessions
    // (the wire omits result_text there) — see ToolResult.displayText.
    val outputText: String? = null,
    // Red-team SF-3: only interim-sealed rows may be grown by a superseding final
    // (desktop gates on existing.interim — use-message-stream/index.ts:674).
    val interimSealed: Boolean = false,
)

/**
 * Q2 (QA 2026-09-14): pure streaming-segment state, mirroring the desktop — interim
 * assistant text is sealed as its OWN segment (hermes-agent tui_gateway/prompt_turn.py:526-533)
 * instead of overwriting the streamed anchor, and message.complete replaces only the newest
 * live anchor. Streamed content never vanishes and never duplicates.
 */
object ChatStream {

  /** Seal a message.interim {text, already_streamed}. Blank text seals nothing (D4 phantom-pill gate). */
  fun sealInterim(
      items: List<ChatItem>,
      interim: String,
      alreadyStreamed: Boolean,
      nextId: () -> String,
  ): List<ChatItem> {
    if (interim.isBlank()) return items
    val idx = items.indexOfLast { it.streaming && it.kind == ItemKind.ASSISTANT }
    if (idx < 0) {
      // No live anchor: nothing is rendered for this stream yet, so show the text. (An
      // already_streamed interim with no anchor means the deltas never made it here.)
      return items + sealed(interim, nextId)
    }
    val out = items.toMutableList()
    val anchor = items[idx]
    var insertAt = idx
    if (anchor.text.isNotBlank()) {
      // streamed content stays, sealed as its own segment (interim-marked: a superseding
      // final may grow it — desktop existing.interim)
      out[idx] = anchor.copy(streaming = false, interimSealed = true)
      insertAt = idx + 1
    } else {
      out.removeAt(idx) // invisible blank anchor: drop, the fresh anchor below replaces it
    }
    if (!alreadyStreamed) {
      // already_streamed=false: the text never went out as deltas — seal it as its own segment.
      out.add(insertAt, sealed(interim, nextId, interimSealed = true))
      insertAt++
    }
    out.add(insertAt, ChatItem(nextId(), ItemKind.ASSISTANT, "", streaming = true))
    return out
  }

  /**
   * Settle a message.complete: replaces ONLY the newest live anchor; sealed segments stay
   * untouched — except when the server supersedes/extends the last sealed interim (desktop
   * finalContinuesInterim, use-message-stream/index.ts:674): the final equals or starts with
   * that sealed segment's text, and the DB keeps ONE row, so live UI must agree — grow the
   * sealed segment in place instead of showing the same text twice.
   */
  fun completeAnchor(items: List<ChatItem>, value: String, nextId: () -> String): List<ChatItem> {
    val idx = items.indexOfLast { it.streaming && it.kind == ItemKind.ASSISTANT }
    if (idx < 0) {
      if (value.isBlank()) return items
      return items + sealed(value, nextId)
    }
    val cur = items[idx]
    if (cur.text.isBlank() && value.isBlank()) {
      return items.toMutableList().also { it.removeAt(idx) } // nothing ever rendered — no phantom pill
    }
    if (idx > 0 && cur.text.isBlank() && value.isNotBlank()) {
      val prev = items[idx - 1]
      val superseded = prev.kind == ItemKind.ASSISTANT && !prev.streaming && prev.interimSealed &&
          prev.text.isNotBlank() &&
          (value == prev.text || value.startsWith(prev.text))
      if (superseded) {
        return items.toMutableList().also {
          it[idx - 1] = prev.copy(text = value) // same row id, full final text
          it.removeAt(idx) // the blank anchor folds away — one row, no duplicate
        }
      }
    }
    // Never blank already-rendered content: an empty final keeps whatever streamed in.
    val text = value.ifBlank { cur.text }
    return items.toMutableList().also { it[idx] = cur.copy(text = text, streaming = false) }
  }

  private fun sealed(text: String, nextId: () -> String, interimSealed: Boolean = false): ChatItem =
      ChatItem(nextId(), ItemKind.ASSISTANT, text, streaming = false, interimSealed = interimSealed)
}

/**
 * Q12 (QA 2026-09-14): the tool.complete wire payload carries NO structured success/failure
 * field (hermes-agent tui_gateway/tool_progress.py:243-267 — {tool_id, name, args,
 * duration_s?, result, summary?, result_text?, inline_diff?}); failure is only derivable from
 * the `result` payload. This mirrors the desktop's own heuristic (agent/display.py
 * `_detect_tool_failure`, :905): terminal non-zero exit_code, JSON `"success": false`, or a
 * result string starting "Error". Conservative subset — no substring sniffing, so partial
 * matches inside larger payloads never mark a tool failed.
 */
object ToolResult {
  fun isFailure(toolName: String?, result: JsonElement?): Boolean {
    if (result == null) return false
    return when (result) {
      is JsonObject -> fromObject(toolName, result)
      is JsonPrimitive -> fromText(toolName, result.content)
      else -> false
    }
  }

  private fun fromObject(toolName: String?, obj: JsonObject): Boolean {
    if (toolName == "terminal") {
      val code = (obj["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return false
      return code != 0
    }
    val success = obj["success"] as? JsonPrimitive ?: return false
    return success.content.toBooleanStrictOrNull() == false
  }

  private fun fromText(toolName: String?, raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{")) {
      val obj = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
      if (obj != null) return fromObject(toolName, obj)
    }
    return trimmed.startsWith("Error")
  }

  private val textKeys = setOf("output", "stdout", "stderr", "text", "text_summary", "content")

  /**
   * Q7 follow-up (live QA 2026-09-14): non-verbose sessions never receive args_text/
   * result_text/summary for most tools (tool_progress.py emits them only when the session
   * is verbose), so the chip would never be expandable. Flatten the ALWAYS-present
   * `result` payload into detail text instead. Known text keys only — unknown JSON
   * shapes stay hidden rather than dumping raw protocol into the UI. Capped: raw `result`
   * is uncapped on the wire, and the whole string lives in the ChatItem.
   */
  fun displayText(result: JsonElement?): String? {
    return when (result) {
      null -> null
      is JsonNull -> null
      is JsonPrimitive -> clamp(result.content)
      is JsonObject -> result.entries
        .asSequence()
        .filter { it.key in textKeys }
        .mapNotNull { entry ->
          // JsonNull IS a JsonPrimitive (content "null") — filter it before clamping.
          if (entry.value is JsonNull) null
          else clamp((entry.value as? JsonPrimitive)?.content)
        }
        .firstOrNull()
      else -> null
    }
  }

  /** The command a tool ran, from the always-present `args` payload (terminal_tool.py:1276). */
  fun commandFromArgs(toolName: String?, args: JsonElement?): String? {
    if (args !is JsonObject) return null
    // execute_code is the real tool name (code_execution_tool.py:862); desktop matches the
    // same pair (run-summary.ts:51).
    if (toolName != "terminal" && toolName != "execute_code") return null
    val raw = (args["command"] ?: args["code"]) as? JsonPrimitive ?: return null
    return clamp(raw.content)
  }

  private fun clamp(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return if (raw.length > MAX_DETAIL_CHARS) raw.take(MAX_DETAIL_CHARS) + "…(truncated)" else raw
  }

  private const val MAX_DETAIL_CHARS = 20_000
}

/**
 * Q6/Q13 (QA 2026-09-14): rewrites server-authored history text into the live, humane
 * representations. Tolerant prefix parsing — the exact format is never assumed.
 */
object HistoryDisplay {
  private val resumedRow = Regex("^↻\\s*Resumed session\\b")
  private val resumedCounts = Regex("\\((\\d+)\\s+user\\s+messages?[^,]*,\\s*(\\d+)\\s+total\\b")
  private val attachedImage = Regex("\\[User attached image: ([^\\]]+)\\]")

  /**
   * "↻ Resumed session {id} "Title" (6 user messages, 31 total messages)"
   * (authored at hermes_cli/cli_agent_setup_mixin.py:480-486) → humane copy without the id.
   */
  fun assistant(text: String): String {
    val trimmed = text.trim()
    if (!resumedRow.containsMatchIn(trimmed)) return text
    val counts = resumedCounts.find(trimmed)?.groupValues
    return if (counts != null) {
      "↻ Resumed this conversation — ${counts[1]} of your messages, ${counts[2]} total."
    } else {
      "↻ Resumed this conversation."
    }
  }

  /** "[User attached image: name]" → the live bubble form "🖼 name" (Q13 unification). */
  fun user(text: String): String = attachedImage.replace(text, "🖼 $1")
}

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
            "user" -> ChatItem("h-$index", ItemKind.USER, HistoryDisplay.user(messageText(m)))
            "assistant" -> ChatItem("h-$index", ItemKind.ASSISTANT, HistoryDisplay.assistant(messageText(m)))
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
