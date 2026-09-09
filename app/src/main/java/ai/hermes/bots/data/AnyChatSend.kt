package ai.hermes.bots.data

import ai.hermes.bots.protocol.RpcException

/**
 * Decision helpers for the AnyChat member send path (FLEET-CONNECT-SPEC B2 self-heal).
 * A gateway restart re-mints live session ids server-side, so a room member's stored
 * canonical session id can go stale: prompt.submit then answers 4001 "session not found"
 * (hermes-agent tui_gateway/methods_prompt.py — the durable-identity fallback there only
 * covers approval.respond, not prompt.submit). Policy: ANY submit failure earns exactly
 * ONE re-resolve + resubmit; after that, surface a humane error instead of dropping the
 * send silently.
 */
object AnyChatSendRetry {
    const val ERR_SESSION_NOT_FOUND = 4001

    fun isStaleSessionError(code: Int?, message: String?): Boolean {
        if (code == ERR_SESSION_NOT_FOUND) return true
        val m = message?.lowercase() ?: return false
        return m.contains("session") &&
            (
                m.contains("not found") ||
                    m.contains("unknown") ||
                    m.contains("no such") ||
                    m.contains("invalid")
                )
    }

    fun isStaleSessionError(e: Throwable): Boolean = when (e) {
        is RpcException -> isStaleSessionError(e.code, e.message)
        else -> isStaleSessionError(null, e.message)
    }

    fun shouldRetry(attempt: Int): Boolean = attempt == 1
}

/**
 * One member's send flow, split out of AnyChatRepository so the stale-session self-heal is
 * unit-testable without Android (HermesGateway/GatewayManager are concrete). The repository
 * supplies [Ops] over the real gateway, roster, and transcript. Every failure path lands
 * in [Ops.error] — a member send is never dropped silently.
 */
internal class AnyChatMemberSend(private val member: AnyChatMember, private val ops: Ops) {

    internal interface Ops {
        /** Member name for humane copy (live from the roster row when known). */
        val displayName: String

        /** True when the member's gateway connection is live right now. */
        fun gatewayLive(): Boolean

        /** prompt.submit to the member's session; throws on any failure. */
        suspend fun submit(sessionId: String, text: String)

        /** session.resume; returns the LIVE session id (server may remap) or null if gone. */
        suspend fun resume(sessionId: String): String?

        /** session.create fallback; returns the new session id or null. */
        suspend fun createSession(profile: String): String?

        /**
         * Bot row for the member; should wait briefly (≤8 s) for the 5 s roster poll to
         * surface a canonical id that differs from [staleSessionId], else return current.
         */
        suspend fun currentRow(staleSessionId: String?): BotRow?

        /** A re-resolved session was adopted — restart collectors, reset the watermark. */
        fun sessionAdopted(sessionId: String)

        /** The member's half-open streaming placeholder is dead — finalize/remove it. */
        fun streamingAborted()

        /** Surface a humane error in the room transcript. */
        fun error(message: String)
    }

    /**
     * Sends [text] for the member. [currentSessionId] is the runtime's id (null = the
     * member's chat never finished opening). Returns true when the text reached a session.
     */
    suspend fun send(text: String, currentSessionId: String?, openIfMissing: () -> Unit): Boolean {
        var sid: String? = currentSessionId
        if (sid == null) {
            ops.streamingAborted()
            ops.error("${ops.displayName} hasn't joined this chat yet — try again in a moment")
            openIfMissing()
            return false
        }
        var attempt = 0
        while (true) {
            attempt += 1
            val target = sid ?: return false // unreachable: retry failure returns in catch
            if (!ops.gatewayLive()) {
                ops.streamingAborted()
                ops.error("${ops.displayName}'s gateway is offline — check Gateways")
                return false
            }
            try {
                ops.submit(target, text)
                return true
            } catch (e: Exception) {
                // The placeholder caret can never outlive a failed submit.
                ops.streamingAborted()
                if (!AnyChatSendRetry.shouldRetry(attempt)) {
                    ops.error("${ops.displayName}: submit failed — ${e.message ?: "rejected"}")
                    return false
                }
                sid = reResolve(staleSessionId = target)
                if (sid == null) {
                    ops.error("${ops.displayName} couldn't be reached — reopen the room")
                    return false
                }
                ops.sessionAdopted(sid)
            }
        }
    }

    /**
     * Fresh canonical session for a member whose submit just failed: wait for a roster
     * canonical id that differs from the stale one, resume it; else create a session —
     * mirroring ChatViewModel.open()'s resume→create fallback.
     */
    private suspend fun reResolve(staleSessionId: String?): String? {
        val row = ops.currentRow(staleSessionId)
        val canonical = row?.canonicalSessionId?.takeIf { it != staleSessionId }
        if (canonical != null) {
            ops.resume(canonical)?.let { return it }
        }
        return ops.createSession(member.botName)
    }
}
