package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.GatewayEvent
import ai.hermes.bots.protocol.GatewayNotReadyException
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.RpcException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Write surface of [ModelHealthStore] so tests can fake persistence (the real store is
 * final + Context-bound); [ModelHealthStoreSink] adapts the store onto it.
 */
interface HealthSink {
    suspend fun markTesting(connectionId: String, provider: String?, model: String?)
    suspend fun record(
        connectionId: String,
        provider: String?,
        model: String?,
        state: HealthState,
        latencyMs: Long?,
        reason: String?,
    )
}

class ModelHealthStoreSink(private val store: ModelHealthStore) : HealthSink {
    override suspend fun markTesting(connectionId: String, provider: String?, model: String?) {
        store.markTesting(connectionId, provider, model)
    }

    override suspend fun record(
        connectionId: String,
        provider: String?,
        model: String?,
        state: HealthState,
        latencyMs: Long?,
        reason: String?,
    ) {
        store.record(connectionId, provider, model, state, latencyMs, reason)
    }
}

/** Verify's classify→record mapping: the decided entry lands under the test's key unchanged. */
internal suspend fun HealthSink.recordEntry(
    connectionId: String,
    provider: String?,
    model: String?,
    entry: HealthEntry,
) {
    record(connectionId, provider, model, entry.state, entry.latencyMs, entry.reason)
}

/**
 * Pure decision helpers for [ModelVerifier] — unit-testable without sockets.
 *
 * Vocabulary (MODEL-UX-PUNCHLIST.md rev 2 / R2): a completion's `error_surface.code`
 * carries the FailoverReason set plus the hand-built runtime `agent_init_failed`
 * (`agent/error_surface.py:27-46,155`, `tui_gateway/methods_prompt.py:481-483`).
 */
internal object ModelVerifierLogic {
    const val TEST_PROMPT = "Health check — reply with exactly: OK"

    /** Turn budget: model verdicts land well under a minute; slow ones are no-response. */
    const val VERIFY_TIMEOUT_MS = 75_000L
    const val NO_RESPONSE_REASON = "No response in time — the model may be down or the key exhausted"
    const val DROPPED_REASON = "Gateway dropped during the test"

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `message.complete` payload → (state, reason) via the shared classifier. */
    fun classifyCompletion(payload: JsonObject): Pair<HealthState, String?> =
        ModelHealth.classify(
            status = payload.str("status"),
            error = payload.str("error"),
            errorSurface = payload["error_surface"] as? JsonObject,
            failureReason = payload.str("failure_reason"),
        )

    /** Last `message.complete` for the test session in a catch-up replay. */
    fun findCompletion(events: List<GatewayEvent>, sessionId: String): GatewayEvent? =
        events.lastOrNull { it.type == Catalog.EVENT_MESSAGE_COMPLETE && it.sessionId == sessionId }

    fun completionEntry(payload: JsonObject, latencyMs: Long?, nowMs: Long): HealthEntry {
        val (state, reason) = classifyCompletion(payload)
        return HealthEntry(state = state, checkedAtMs = nowMs, latencyMs = latencyMs, reason = reason)
    }

    /** Timeout path (R5): a replayed completion classifies; otherwise an honest no-response failure. */
    fun fallbackEntry(events: List<GatewayEvent>, sessionId: String, nowMs: Long): HealthEntry {
        val ev = findCompletion(events, sessionId)
            ?: return HealthEntry(
                state = HealthState.FAILED,
                checkedAtMs = nowMs,
                latencyMs = null,
                reason = NO_RESPONSE_REASON,
            )
        return completionEntry(ev.payload, latencyMs = null, nowMs = nowMs)
    }

    /** Connection loss mid-test is UNTESTED — never a verdict against the model (R5). */
    fun droppedEntry(nowMs: Long): HealthEntry = HealthEntry(
        state = HealthState.UNTESTED,
        checkedAtMs = nowMs,
        latencyMs = null,
        reason = DROPPED_REASON,
    )

    /** The test session itself could not run (unknown profile, session cap, …) — UNTESTED. */
    fun startFailureEntry(rawDetail: String?, nowMs: Long): HealthEntry = HealthEntry(
        state = HealthState.UNTESTED,
        checkedAtMs = nowMs,
        latencyMs = null,
        reason = startFailureReason(rawDetail),
    )

    fun startFailureReason(rawDetail: String?): String {
        val one = rawDetail?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (one.isBlank()) "Could not start a test session on the gateway"
        else "Could not start a test session — ${one.take(120)}"
    }
}

/**
 * Verifies a bot's SAVED model pin end-to-end (MODEL-UX-PUNCHLIST.md W3 / P11, rev 2):
 * ephemeral hidden session on the bot's profile → one short test turn → `message.complete`
 * → classify → [ModelHealthStore]. R1: never sends `model`/`provider` overrides —
 * per-session overrides cannot resolve named-custom providers ("custom:clinepass"
 * live-fails `agent_init_failed` "Unknown provider") while config pins resolve, so the
 * only true test is against what the bot actually runs.
 *
 * [verify] never throws for TEST failures — those are HealthEntry results; only
 * "connection not live" throws.
 */
class ModelVerifier(
    private val manager: GatewayManager,
    private val health: HealthSink,
) {
    constructor(manager: GatewayManager, store: ModelHealthStore) : this(manager, ModelHealthStoreSink(store))

    private fun now(): Long = System.currentTimeMillis()

    /**
     * Runs one test turn for [profileSlug] on [connectionId], recording the outcome under
     * key `connectionId|provider|model` and returning it.
     */
    suspend fun verify(
        connectionId: String,
        profileSlug: String,
        provider: String?,
        model: String?,
    ): HealthEntry {
        val gateway = manager.live.value[connectionId]?.gateway
            ?: throw IllegalStateException("connection not live")
        health.markTesting(connectionId, provider, model)
        var testSid: String? = null
        try {
            // R1: NO model/provider overrides here. Params per tui_gateway/methods_session.py:307-395.
            val created = try {
                gateway.request(
                    Catalog.METHOD_SESSION_CREATE,
                    buildJsonObject {
                        put("profile", profileSlug)
                        put("hidden", true)
                        put("close_on_disconnect", true)
                        put("title", "hv-$profileSlug-${System.currentTimeMillis()}")
                    },
                )
            } catch (e: GatewayNotReadyException) {
                return record(connectionId, provider, model, ModelVerifierLogic.droppedEntry(now()))
            } catch (e: IOException) {
                return record(connectionId, provider, model, ModelVerifierLogic.droppedEntry(now()))
            } catch (e: RpcException) {
                return record(connectionId, provider, model, ModelVerifierLogic.startFailureEntry(e.message, now()))
            }
            testSid = (created["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return record(connectionId, provider, model, ModelVerifierLogic.startFailureEntry(null, now()))
            return runTurn(gateway, connectionId, testSid, provider, model)
        } finally {
            // R12: best-effort close (max_live_sessions: 16); close_on_disconnect above is
            // belt-and-braces for a socket that dies before we get here.
            testSid?.let { sid ->
                runCatching {
                    gateway.request(
                        Catalog.METHOD_SESSION_CLOSE,
                        buildJsonObject { put("session_id", sid) },
                    )
                }
            }
        }
    }

    /**
     * Verifies a CANDIDATE (provider, model) pair that is not yet any bot's saved pin —
     * the editor's "which ✓ providers actually work" check. Unlike [verify], this sends
     * per-session `model`/`provider` overrides, which resolve for REGISTRY providers
     * (anthropic, copilot, opencode-free, …) but never for named-custom ones
     * ("custom:clinepass" live-fails `agent_init_failed` "Unknown provider" — R1), so
     * callers must not invoke this for `custom*` slugs; those are proven via the
     * gateway's own pin / Same-as chips.
     *
     * Outcome is recorded under model key "" (the provider-level entry) and, on success,
     * duplicated under the winning model so model rows can show "verified".
     */
    suspend fun verifyCandidate(
        connectionId: String,
        provider: String,
        model: String,
    ): HealthEntry {
        require(!provider.trim().lowercase().startsWith("custom")) {
            "candidate probes cannot resolve custom providers — test the saved pin instead"
        }
        val gateway = manager.live.value[connectionId]?.gateway
            ?: throw IllegalStateException("connection not live")
        health.markTesting(connectionId, provider, "")
        var testSid: String? = null
        try {
            val created = try {
                gateway.request(
                    Catalog.METHOD_SESSION_CREATE,
                    buildJsonObject {
                        put("model", model)
                        put("provider", provider)
                        put("hidden", true)
                        put("close_on_disconnect", true)
                        put("title", "hv-probe-$provider-${System.currentTimeMillis()}")
                    },
                )
            } catch (e: GatewayNotReadyException) {
                return record(connectionId, provider, "", ModelVerifierLogic.droppedEntry(now()))
            } catch (e: IOException) {
                return record(connectionId, provider, "", ModelVerifierLogic.droppedEntry(now()))
            } catch (e: RpcException) {
                return record(connectionId, provider, "", ModelVerifierLogic.startFailureEntry(e.message, now()))
            }
            testSid = (created["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return record(connectionId, provider, "", ModelVerifierLogic.startFailureEntry(null, now()))
            val entry = runTurn(gateway, connectionId, testSid, provider, "")
            if (entry.state == HealthState.WORKING) {
                // Duplicate the win under the exact model so dropdown rows show "verified".
                record(connectionId, provider, model, entry)
            }
            return entry
        } finally {
            testSid?.let { sid ->
                runCatching {
                    gateway.request(
                        Catalog.METHOD_SESSION_CLOSE,
                        buildJsonObject { put("session_id", sid) },
                    )
                }
            }
        }
    }

    private suspend fun runTurn(
        gateway: HermesGateway,
        connectionId: String,
        testSid: String,
        provider: String?,
        model: String?,
    ): HealthEntry = coroutineScope {
        val completion = CompletableDeferred<GatewayEvent>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch {
            gateway.events
                .onSubscription { subscribed.complete(Unit) }
                .filter { it.type == Catalog.EVENT_MESSAGE_COMPLETE && it.sessionId == testSid }
                .collect { ev -> completion.complete(ev) }
        }
        val entry = try {
            // R5: the filtered collector MUST be subscribed before prompt.submit — events is
            // SharedFlow(replay=0) and failed builds emit message.complete in <100 ms, so a
            // late subscriber never sees it. The 2 s cap just guarantees no hang; a missed
            // subscription is still covered by the since-replay fallback below.
            try {
                withTimeout(2_000) { subscribed.await() }
            } catch (e: TimeoutCancellationException) {
                Unit
            }
            val startedAt = now()
            val ev = try {
                withTimeout(ModelVerifierLogic.VERIFY_TIMEOUT_MS) {
                    gateway.request(
                        Catalog.METHOD_PROMPT_SUBMIT,
                        buildJsonObject {
                            put("session_id", testSid)
                            put("text", ModelVerifierLogic.TEST_PROMPT)
                            // R12 — exact spelling verified: display_kind is whitelisted to
                            // "hidden" (tui_gateway/methods_prompt.py:544-552).
                            put("display_kind", "hidden")
                        },
                        ModelVerifierLogic.VERIFY_TIMEOUT_MS,
                    )
                    completion.await()
                }
            } catch (e: TimeoutCancellationException) {
                null // fall through to the since-replay fallback below (R5)
            }
            if (ev != null) {
                ModelVerifierLogic.completionEntry(ev.payload, now() - startedAt, now())
            } else {
                // Timeout → replay the session log before declaring failure (R5). A dropped
                // socket here propagates to the catch below → UNTESTED, never FAILED.
                val catchUp = gateway.since(testSid, 0)
                ModelVerifierLogic.fallbackEntry(catchUp.events, testSid, now())
            }
        } catch (e: GatewayNotReadyException) {
            ModelVerifierLogic.droppedEntry(now())
        } catch (e: IOException) {
            ModelVerifierLogic.droppedEntry(now())
        } catch (e: RpcException) {
            // Submit-level RPC failure (busy/cap/…) — the turn never ran; not a model verdict.
            ModelVerifierLogic.startFailureEntry(e.message, now())
        } finally {
            collector.cancel()
        }
        record(connectionId, provider, model, entry)
        entry
    }

    private suspend fun record(
        connectionId: String,
        provider: String?,
        model: String?,
        entry: HealthEntry,
    ): HealthEntry {
        health.recordEntry(connectionId, provider, model, entry)
        return entry
    }
}
