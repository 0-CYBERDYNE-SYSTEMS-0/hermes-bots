package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One blocking prompt (approval/clarify/sudo/secret — PROTOCOL.md §5.5) waiting on the
 * user, gathered across ALL connections for the Activity screen's "Needs you" section
 * (UI-SPEC.md §4.6). Fed by the per-connection gateway event collectors in AppGraph.
 */
data class PendingApproval(
  val connectionId: String,
  val sessionId: String?,
  val botName: String?,
  val card: ApprovalCard,
  val atMs: Long,
  /** `*.expire` arrived: the row stays visible but grays and loses its quick actions. */
  val expired: Boolean = false,
)

/**
 * Socket seam so state transitions are unit-testable without sockets: production resolves
 * the live gateway for [connectionId]; tests record calls with a lambda.
 */
fun interface ApprovalRpcSender {
  suspend fun send(connectionId: String, method: String, params: JsonObject)
}

/**
 * App-side inbox of pending blocking prompts. Dedupes by requestId (a server re-push
 * updates the row in place, keeping the original arrival age), marks rows expired on
 * `*.expire`, and removes a row only after a successful respond — so a failed respond
 * (gateway offline) leaves the prompt retryable.
 */
class ApprovalInbox(
  private val sender: ApprovalRpcSender,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  private val _pending = MutableStateFlow<List<PendingApproval>>(emptyList())

  /** Newest-first snapshot of prompts waiting on the user. */
  val pending: StateFlow<List<PendingApproval>> = _pending

  /**
   * Record a blocking-prompt push. Known requestIds update in place (refreshed bot
   * binding + payload, original arrival age preserved); unknown ones append. Returns
   * the stored row, or null for a payloadless/blank requestId card.
   */
  fun onBlockingPrompt(
    connectionId: String,
    botName: String?,
    sessionId: String?,
    card: ApprovalCard,
  ): PendingApproval? {
    if (card.requestId.isBlank()) return null
    val existing = _pending.value.firstOrNull { it.card.requestId == card.requestId }
    val entry = if (existing != null) {
      existing.copy(
        connectionId = connectionId,
        botName = botName,
        sessionId = sessionId,
        card = card,
        expired = false,
      )
    } else {
      PendingApproval(
        connectionId = connectionId,
        sessionId = sessionId,
        botName = botName,
        card = card,
        atMs = nowMs(),
      )
    }
    _pending.update { current ->
      val others = current.filterNot { it.card.requestId == card.requestId }
      (others + entry).sortedByDescending { it.atMs }
    }
    return entry
  }

  /** `*.expire {request_id}`: gray the row (kept visible) rather than dropping it. */
  fun onExpire(requestId: String) {
    if (requestId.isBlank()) return
    _pending.update { current ->
      current.map { if (it.card.requestId == requestId) it.copy(expired = true) else it }
    }
  }

  /** Drop the row outright (e.g. resolved elsewhere). */
  fun remove(requestId: String) {
    if (requestId.isBlank()) return
    _pending.update { current -> current.filterNot { it.card.requestId == requestId } }
  }

  /**
   * Answer a prompt on its owning gateway socket — clarify prompts mirror the
   * clarify.request payload (`answer`), everything else rides approval.respond with
   * `choice` (PROTOCOL.md §5.5, ChatViewModel.respond mechanics). The row is removed
   * only on success; failures propagate to the caller.
   */
  suspend fun respond(pending: PendingApproval, choice: String) {
    val clarify = pending.card.kind == Catalog.EVENT_CLARIFY_REQUEST
    val params = buildJsonObject {
      pending.sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) }
      put("request_id", pending.card.requestId)
      if (clarify) put("answer", choice) else put("choice", choice)
    }
    val method = if (clarify) Catalog.METHOD_CLARIFY_RESPOND else Catalog.METHOD_APPROVAL_RESPOND
    sender.send(pending.connectionId, method, params)
    remove(pending.card.requestId)
  }
}
