package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Canonical "Bot Chat" helpers (BOTS-MODE-PARITY.md §2; PROTOCOL.md §5.2-5.5). */
object CanonicalChat {

    /** Typing /new inside a canonical chat is rerouted to session.compress — never a fork. */
    fun isOpenCommand(text: String): Boolean = text.trim() == "/new"

    fun createParams(profile: String): JsonObject = buildJsonObject {
        put("title", Catalog.CANONICAL_CHAT_TITLE)
        put("hidden", true)
        put("profile", profile)
        put("close_on_disconnect", false) // DECISIONS.md #7: survive mobile disconnects
    }

    fun compressParams(sessionId: String): JsonObject = buildJsonObject { put("session_id", sessionId) }

    fun submitParams(sessionId: String, text: String): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("text", text)
    }

    /**
     * approval.request / clarify.request / sudo.request / secret.request payload → card.
     *
     * Q11 (QA 2026-09-14): choices are NEVER fabricated. Contract: approval.request payloads
     * always carry choices — the server fills a missing set with [once (+session) (+always)
     * +deny] before emitting (hermes-agent tui_gateway/server.py:629-640 `_approval_request_payload`,
     * emit at :688; replay snapshot :671-679) — so a real approval never lands empty. Only a
     * free-text clarify arrives with no choices, and sending a fabricated "once" as the ANSWER
     * to an open question is wrong (the desktop shows a typed input there). Empty choices render
     * a humane "reply in chat" affordance instead (ApprovalCardView / Activity Needs-you).
     *
     * QA 2026-09-19: tolerate the real clarify shapes the wire actually carries —
     * - batch clarify: `questions: [{qid, question, choices, multi_select}]`
     *   (tui_gateway/server.py `_clarify_block`) — the card shows the first entry;
     * - plain `text`/`title` instead of `question`;
     * - the 0.21.3+ JSON-RPC dialect: HermesGateway re-emits those requests with
     *   `server_request: true` + request_id = srq id, and the card records it as
     *   [ApprovalCard.serverRequestId] so answers ride a result frame.
     */
    fun parseCard(kind: String, payload: JsonObject?): ApprovalCard? {
        if (payload == null) return null
        val requestId = stringField(payload, "request_id") ?: return null
        val batch = (payload["questions"] as? JsonArray)
            ?.firstOrNull() as? JsonObject
        val command = stringField(payload, "command")
            ?: stringField(payload, "question")
            ?: stringField(payload, "text")
            ?: batch?.let { stringField(it, "question") }
        val choices = stringList(payload, "choices")
            ?: stringList(payload, "answers")
            ?: batch?.let { stringList(it, "choices") }
            ?: emptyList()
        val serverRequest = (payload["server_request"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        return ApprovalCard(
            requestId = requestId,
            kind = kind,
            command = command,
            choices = choices,
            serverRequestId = if (serverRequest) requestId else null,
            qid = batch?.let { stringField(it, "qid") },
        )
    }

    private fun stringField(payload: JsonObject, key: String): String? =
        (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun stringList(payload: JsonObject, key: String): List<String>? =
        (payload[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.takeIf { it.isNotEmpty() }
}
