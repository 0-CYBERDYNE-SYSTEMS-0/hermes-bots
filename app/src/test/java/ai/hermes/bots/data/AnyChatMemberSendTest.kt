package ai.hermes.bots.data

import ai.hermes.bots.protocol.RpcError
import ai.hermes.bots.protocol.RpcException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AnyChatMemberSendTest {

    private val member = AnyChatMember(connectionId = "gw9120", botName = "default")

    private class FakeOps(override val displayName: String = "default") : AnyChatMemberSend.Ops {
        var live = true
        val attempts = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val adopted = mutableListOf<String>()
        val staleIdsAsked = mutableListOf<String?>()
        var streamingAbortedCount = 0
        var openedCount = 0
        var row: BotRow? = null
        var resumeResult: String? = null
        var createResult: String? = null
        val submitFailures = ArrayDeque<Exception>()

        override fun gatewayLive(): Boolean = live

        override suspend fun submit(sessionId: String, text: String) {
            attempts += sessionId
            submitFailures.removeFirstOrNull()?.let { throw it }
            submitted += sessionId
        }

        override suspend fun resume(sessionId: String): String? = resumeResult

        override suspend fun createSession(profile: String): String? = createResult

        override suspend fun currentRow(staleSessionId: String?): BotRow? {
            staleIdsAsked += staleSessionId
            return row
        }

        override fun sessionAdopted(sessionId: String) {
            adopted += sessionId
        }

        override fun streamingAborted() {
            streamingAbortedCount += 1
        }

        override fun error(message: String) {
            errors += message
        }
    }

    private fun row(canonicalSessionId: String?) = BotRow(
        connectionId = "gw9120",
        name = "default",
        displayName = "Default",
        description = null,
        model = null,
        provider = null,
        skillCount = 0,
        isDefault = true,
        hasAvatar = false,
        sectionId = null,
        hidden = false,
        lastPreview = null,
        lastActiveMs = null,
        workerActiveMs = null,
        canonicalSessionId = canonicalSessionId,
        canonicalRootTitle = null,
        uiMetaRevisions = emptyMap(),
    )

    private fun staleSession() = RpcException(RpcError(4001, "session not found"))

    @Test
    fun `happy path submits once and touches nothing else`() = runTest {
        val ops = FakeOps()
        val result = AnyChatMemberSend(member, ops).send("hi", "canon-1", openIfMissing = { ops.openedCount++ })
        assertTrue(result)
        assertEquals(listOf("canon-1"), ops.attempts)
        assertEquals(listOf("canon-1"), ops.submitted)
        assertEquals(emptyList<String>(), ops.errors)
        assertEquals(0, ops.streamingAbortedCount)
        assertEquals(0, ops.openedCount)
    }

    @Test
    fun `stale session 4001 re-resolves to fresh canonical id and resubmits once`() = runTest {
        val ops = FakeOps().apply {
            row = row(canonicalSessionId = "canon-2")
            resumeResult = "canon-2"
            submitFailures += staleSession()
        }
        val result = AnyChatMemberSend(member, ops).send("hi", "canon-1", openIfMissing = { ops.openedCount++ })
        assertTrue(result)
        // asked the roster for a row fresher than the stale id…
        assertEquals(listOf<String?>("canon-1"), ops.staleIdsAsked)
        // …attempted exactly twice, resubmitting on the healed session…
        assertEquals(listOf("canon-1", "canon-2"), ops.attempts)
        assertEquals(listOf("canon-2"), ops.submitted)
        // …and adopted it (collectors restart, watermark reset).
        assertEquals(listOf("canon-2"), ops.adopted)
        assertEquals(emptyList<String>(), ops.errors)
    }

    @Test
    fun `stale id still in roster falls back to session create`() = runTest {
        val ops = FakeOps().apply {
            row = row(canonicalSessionId = "canon-1") // unchanged — still the stale id
            createResult = "fresh-created"
            submitFailures += staleSession()
        }
        val result = AnyChatMemberSend(member, ops).send("hi", "canon-1", openIfMissing = { ops.openedCount++ })
        assertTrue(result)
        assertEquals(listOf("canon-1", "fresh-created"), ops.attempts)
        assertEquals(listOf("fresh-created"), ops.submitted)
        assertEquals(listOf("fresh-created"), ops.adopted)
        assertEquals(emptyList<String>(), ops.errors)
    }

    @Test
    fun `unresolvable member surfaces reopen-the-room copy exactly once`() = runTest {
        val ops = FakeOps().apply {
            row = row(canonicalSessionId = "canon-1") // stale, unchanged
            resumeResult = null
            createResult = null
            submitFailures += staleSession()
        }
        val result = AnyChatMemberSend(member, ops).send("hi", "canon-1", openIfMissing = { ops.openedCount++ })
        assertFalse(result)
        assertEquals(1, ops.attempts.size) // one retry, not a loop
        assertEquals(listOf("default couldn't be reached — reopen the room"), ops.errors)
    }

    @Test
    fun `member that never opened surfaces try-again and kicks the open path`() = runTest {
        val ops = FakeOps()
        val result = AnyChatMemberSend(member, ops).send("hi", currentSessionId = null, openIfMissing = { ops.openedCount++ })
        assertFalse(result)
        assertEquals(0, ops.submitted.size)
        assertEquals(1, ops.openedCount)
        assertEquals(listOf("default hasn't joined this chat yet — try again in a moment"), ops.errors)
    }

    @Test
    fun `offline gateway surfaces check-gateways copy without submitting`() = runTest {
        val ops = FakeOps().apply { live = false }
        val result = AnyChatMemberSend(member, ops).send("hi", "canon-1", openIfMissing = { ops.openedCount++ })
        assertFalse(result)
        assertEquals(0, ops.submitted.size)
        assertEquals(listOf("default's gateway is offline — check Gateways"), ops.errors)
        assertTrue(ops.streamingAbortedCount >= 1)
    }

    @Test
    fun `any submit failure earns exactly one retry then gives up with detail`() = runTest {
        val ops = FakeOps().apply {
            row = row(canonicalSessionId = "canon-1") // stale, unchanged…
            createResult = "fresh-created"            // …but create self-heal finds a session
            submitFailures += IOException("socket gone")
            submitFailures += IOException("still gone")
        }
        val result = AnyChatMemberSend(member, ops).send("hi", "canon-1", openIfMissing = { ops.openedCount++ })
        assertFalse(result)
        assertEquals(listOf("canon-1", "fresh-created"), ops.attempts) // retry-once policy
        assertEquals(0, ops.submitted.size)
        assertEquals(listOf("default: submit failed — still gone"), ops.errors)
    }

    @Test
    fun `failed submit finalizes the streaming placeholder`() = runTest {
        val ops = FakeOps().apply {
            live = false
        }
        AnyChatMemberSend(member, ops).send("hi", "canon-1", openIfMissing = { ops.openedCount++ })
        assertEquals(1, ops.streamingAbortedCount)
    }
}

class AnyChatSendRetryTest {
    @Test
    fun `classification covers 4001 and session-not-found message shapes`() {
        assertTrue(AnyChatSendRetry.isStaleSessionError(4001, null))
        assertTrue(AnyChatSendRetry.isStaleSessionError(null, "Session not found"))
        assertTrue(AnyChatSendRetry.isStaleSessionError(7, "unknown session id"))
        assertTrue(AnyChatSendRetry.isStaleSessionError(null, "no such session"))
        assertFalse(AnyChatSendRetry.isStaleSessionError(-32602, "invalid params"))
        assertTrue(AnyChatSendRetry.isStaleSessionError(RpcException(RpcError(4001, "session not found"))))
        assertFalse(AnyChatSendRetry.isStaleSessionError(IOException("socket gone")))
    }

    @Test
    fun `retry policy is exactly one self-heal attempt`() {
        assertTrue(AnyChatSendRetry.shouldRetry(1))
        assertFalse(AnyChatSendRetry.shouldRetry(2))
        assertFalse(AnyChatSendRetry.shouldRetry(3))
    }
}
