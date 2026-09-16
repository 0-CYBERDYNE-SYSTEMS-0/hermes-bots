package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.GatewayEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic of the model verifier (MODEL-UX-PUNCHLIST.md rev 2, R2/R5/R12). */
class ModelVerifierTest {

    // --- classifyCompletion: message.complete payload → verdict (R2 vocabulary) ---

    @Test
    fun `successful completion classifies WORKING with no reason`() {
        val (state, reason) = ModelVerifierLogic.classifyCompletion(
            buildJsonObject { put("text", "OK"); put("status", "complete") },
        )
        assertEquals(HealthState.WORKING, state)
        assertNull(reason)
    }

    @Test
    fun `model_not_found surface maps to endpoint rejection`() {
        val (state, reason) = ModelVerifierLogic.classifyCompletion(
            buildJsonObject {
                put("text", "invalid model format. Expected format: modelType/model")
                put("status", "error")
                put("error", "invalid model format")
                put("error_surface", buildJsonObject {
                    put("layer", "provider")
                    put("code", "model_not_found")
                    put("retryable", false)
                })
            },
        )
        assertEquals(HealthState.FAILED, state)
        assertEquals("The endpoint rejected that model id", reason)
    }

    @Test
    fun `agent_init_failed unknown provider maps to provider hint`() {
        // Live-probe trap (R1): overrides cannot resolve named-custom providers.
        val (state, reason) = ModelVerifierLogic.classifyCompletion(
            buildJsonObject {
                put("status", "error")
                put("error", "Unknown provider 'custom:clinepass'")
                put("error_surface", buildJsonObject {
                    put("layer", "runtime")
                    put("code", "agent_init_failed")
                    put("retryable", true)
                })
            },
        )
        assertEquals(HealthState.FAILED, state)
        // Named-custom spelling: resolves only for the launch home — copy points at the list.
        assertEquals(
            "This provider spelling only works for the gateway's own default bot — pick one from the list",
            reason,
        )
    }

    @Test
    fun `billing surface maps to credits reason`() {
        val (state, reason) = ModelVerifierLogic.classifyCompletion(
            buildJsonObject {
                put("status", "error")
                put("error", "insufficient quota")
                put("error_surface", buildJsonObject {
                    put("layer", "billing"); put("code", "billing"); put("retryable", false)
                })
            },
        )
        assertEquals(HealthState.FAILED, state)
        assertEquals("Out of credits or billing blocked", reason)
    }

    @Test
    fun `auth surface maps to key reason`() {
        val (state, reason) = ModelVerifierLogic.classifyCompletion(
            buildJsonObject {
                put("status", "error")
                put("error", "401 unauthorized")
                put("error_surface", buildJsonObject {
                    put("layer", "auth"); put("code", "auth"); put("retryable", false)
                })
            },
        )
        assertEquals(HealthState.FAILED, state)
        assertEquals("Key rejected or missing on the gateway", reason)
    }

    @Test
    fun `failure_reason is the fallback when surface absent`() {
        val (state, reason) = ModelVerifierLogic.classifyCompletion(
            buildJsonObject { put("status", "error"); put("error", "upstream exploded") },
        )
        // failure_reason absent → last-resort raw sniff.
        assertEquals(HealthState.FAILED, state)
        assertEquals("upstream exploded", reason)

        val (state2, reason2) = ModelVerifierLogic.classifyCompletion(
            buildJsonObject {
                put("status", "error")
                put("error", "quota exhausted")
                put("failure_reason", "provider_quota_limit")
            },
        )
        assertEquals(HealthState.FAILED, state2)
        assertEquals("Out of credits or over limit on the gateway's key", reason2)
    }

    // --- findCompletion / fallbackEntry: the R5 timeout path ---

    private fun ev(type: String, sid: String?, payload: JsonObject = buildJsonObject {}) =
        GatewayEvent(type = type, sessionId = sid, seq = 1L, payload = payload)

    @Test
    fun `findCompletion picks last message_complete for the test session`() {
        val ok = buildJsonObject { put("text", "OK"); put("status", "complete") }
        val events = listOf(
            ev(Catalog.EVENT_MESSAGE_DELTA, "s1"),
            ev(Catalog.EVENT_MESSAGE_COMPLETE, "s2"),
            ev(Catalog.EVENT_MESSAGE_COMPLETE, "s1"),
            ev(Catalog.EVENT_MESSAGE_DELTA, "s1"),
            ev(Catalog.EVENT_MESSAGE_COMPLETE, "s1", ok),
        )
        assertEquals(ok, ModelVerifierLogic.findCompletion(events, "s1")?.payload)
    }

    @Test
    fun `fallback with no replayed completion records honest no-response failure`() {
        val entry = ModelVerifierLogic.fallbackEntry(
            listOf(ev(Catalog.EVENT_MESSAGE_START, "s1"), ev(Catalog.EVENT_MESSAGE_DELTA, "s1")),
            sessionId = "s1",
            nowMs = 1234L,
        )
        assertEquals(HealthState.FAILED, entry.state)
        assertNull(entry.latencyMs)
        assertEquals(1234L, entry.checkedAtMs)
        assertEquals(ModelVerifierLogic.NO_RESPONSE_REASON, entry.reason)
    }

    @Test
    fun `fallback classifies a replayed completion`() {
        val bad = buildJsonObject {
            put("status", "error")
            put("error", "invalid model format")
            put("error_surface", buildJsonObject {
                put("layer", "provider"); put("code", "model_not_found"); put("retryable", false)
            })
        }
        val entry = ModelVerifierLogic.fallbackEntry(
            listOf(ev(Catalog.EVENT_MESSAGE_COMPLETE, "s1", bad)),
            sessionId = "s1",
            nowMs = 9L,
        )
        assertEquals(HealthState.FAILED, entry.state)
        assertEquals("The endpoint rejected that model id", entry.reason)
    }

    @Test
    fun `fallback over empty replay is FAILED no-response`() {
        val entry = ModelVerifierLogic.fallbackEntry(emptyList(), sessionId = "s1", nowMs = 5L)
        assertEquals(HealthState.FAILED, entry.state)
        assertEquals(ModelVerifierLogic.NO_RESPONSE_REASON, entry.reason)
    }

    // --- connection-loss / start-failure entries: UNTESTED, never FAILED (R5) ---

    @Test
    fun `dropped entry is UNTESTED not FAILED`() {
        val entry = ModelVerifierLogic.droppedEntry(9L)
        assertEquals(HealthState.UNTESTED, entry.state)
        assertEquals("Gateway dropped during the test", entry.reason)
        assertNull(entry.latencyMs)
        assertEquals(9L, entry.checkedAtMs)
    }

    @Test
    fun `start failure carries trimmed detail and stays UNTESTED`() {
        val entry = ModelVerifierLogic.startFailureEntry("rpc -32603: internal error\n  at handler", 9L)
        assertEquals(HealthState.UNTESTED, entry.state)
        assertTrue(entry.reason!!.startsWith("Could not start a test session"))
        assertTrue(entry.reason!!.contains("internal error"))
        assertTrue(entry.reason!!.length < 160)
    }

    @Test
    fun `blank start failure detail falls back to generic text`() {
        val entry = ModelVerifierLogic.startFailureEntry(null, 9L)
        assertEquals(HealthState.UNTESTED, entry.state)
        assertEquals("Could not start a test session on the gateway", entry.reason)
    }

    // --- completionEntry: latency + timestamp are preserved on both verdicts ---

    @Test
    fun `completionEntry carries latency and timestamp for WORKING`() {
        val entry = ModelVerifierLogic.completionEntry(
            buildJsonObject { put("text", "OK"); put("status", "complete") },
            latencyMs = 900L,
            nowMs = 42L,
        )
        assertEquals(HealthState.WORKING, entry.state)
        assertEquals(900L, entry.latencyMs)
        assertEquals(42L, entry.checkedAtMs)
        assertNull(entry.reason)
    }

    @Test
    fun `completionEntry carries latency for FAILED too`() {
        val entry = ModelVerifierLogic.completionEntry(
            buildJsonObject {
                put("status", "error")
                put("error", "invalid model format")
                put("error_surface", buildJsonObject {
                    put("layer", "provider"); put("code", "model_not_found"); put("retryable", false)
                })
            },
            latencyMs = 412L,
            nowMs = 42L,
        )
        assertEquals(HealthState.FAILED, entry.state)
        assertEquals("The endpoint rejected that model id", entry.reason)
        assertEquals(412L, entry.latencyMs)
    }

    // --- HealthSink mapping: classify → record lands unchanged (fake store) ---

    private class FakeSink : HealthSink {
        data class Recorded(
            val connectionId: String,
            val provider: String?,
            val model: String?,
            val entry: HealthEntry,
        )

        var markTestingCalls = 0
        val recorded = mutableListOf<Recorded>()

        override suspend fun markTesting(connectionId: String, provider: String?, model: String?) {
            markTestingCalls += 1
        }

        override suspend fun record(
            connectionId: String,
            provider: String?,
            model: String?,
            state: HealthState,
            latencyMs: Long?,
            reason: String?,
        ) {
            recorded += Recorded(
                connectionId, provider, model,
                HealthEntry(state = state, checkedAtMs = 0L, latencyMs = latencyMs, reason = reason),
            )
        }
    }

    @Test
    fun `recordEntry maps state latency and reason unchanged under the test key`() = runBlocking {
        val sink = FakeSink()
        sink.markTesting("c1", "custom:clinepass", "deepseek-v4-flash")
        assertEquals(1, sink.markTestingCalls)

        val entry = ModelVerifierLogic.completionEntry(
            buildJsonObject { put("text", "OK"); put("status", "complete") },
            latencyMs = 1234L,
            nowMs = 7L,
        )
        sink.recordEntry("c1", "custom:clinepass", "deepseek-v4-flash", entry)

        val r = sink.recorded.single()
        assertEquals("c1", r.connectionId)
        assertEquals("custom:clinepass", r.provider)
        assertEquals("deepseek-v4-flash", r.model)
        assertEquals(HealthState.WORKING, r.entry.state)
        assertEquals(1234L, r.entry.latencyMs)
        assertNull(r.entry.reason)
    }

    @Test
    fun `recordEntry maps FAILED verdict with reason and no latency on fallback`() = runBlocking {
        val sink = FakeSink()
        val entry = ModelVerifierLogic.fallbackEntry(emptyList(), sessionId = "s1", nowMs = 11L)
        sink.recordEntry("c2", null, null, entry)
        val r = sink.recorded.single()
        assertEquals(HealthState.FAILED, r.entry.state)
        assertEquals(ModelVerifierLogic.NO_RESPONSE_REASON, r.entry.reason)
        assertNull(r.entry.latencyMs)
        assertEquals("c2", r.connectionId)
        assertNull(r.provider)
        assertNull(r.model)
    }
}
