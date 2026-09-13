package ai.hermes.bots.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state transitions of the approval inbox (UI-SPEC.md §4.6 "Needs you"). */
class ApprovalInboxTest {

  private class RecordingSender : ApprovalRpcSender {
    val calls = mutableListOf<Triple<String, String, JsonObject>>()
    var fail = false

    override suspend fun send(connectionId: String, method: String, params: JsonObject) {
      if (fail) throw IOException("gateway offline")
      calls.add(Triple(connectionId, method, params))
    }
  }

  private fun card(
    id: String,
    command: String = "rm -rf /tmp/x",
    choices: List<String> = listOf("once", "deny"),
    kind: String = "approval.request",
  ) = ApprovalCard(requestId = id, kind = kind, command = command, choices = choices)

  private fun inboxWithTick(): Pair<ApprovalInbox, RecordingSender> {
    val sender = RecordingSender()
    var t = 1_000L
    return ApprovalInbox(sender, nowMs = { ++t }) to sender
  }

  @Test
  fun `new prompts append newest-first`() {
    val (inbox, _) = inboxWithTick()
    inbox.onBlockingPrompt("c1", "scout", "s1", card("r1"))
    inbox.onBlockingPrompt("c1", "recon", "s2", card("r2"))
    inbox.onBlockingPrompt("c1", "default", "s3", card("r3"))
    val ids = inbox.pending.value.map { it.card.requestId }
    assertEquals(listOf("r3", "r2", "r1"), ids)
  }

  @Test
  fun `duplicate request id updates in place keeping arrival age`() {
    val (inbox, _) = inboxWithTick()
    inbox.onBlockingPrompt("c1", "scout", "s1", card("r1", command = "first"))
    val firstAt = inbox.pending.value.single().atMs
    inbox.onBlockingPrompt("c1", "scout", "s1", card("r1", command = "second"))
    inbox.onBlockingPrompt("c1", "recon", "s2", card("r2"))
    val rows = inbox.pending.value
    assertEquals(2, rows.size)
    assertEquals("r2", rows.first().card.requestId) // r1 keeps its earlier arrival age
    val updated = rows.last()
    assertEquals("second", updated.card.command)
    assertEquals(firstAt, updated.atMs)
  }

  @Test
  fun `blank request id is rejected`() {
    val (inbox, _) = inboxWithTick()
    assertNull(inbox.onBlockingPrompt("c1", "scout", "s1", card(" ")))
    assertTrue(inbox.pending.value.isEmpty())
  }

  @Test
  fun `expire grays the row without removing it`() {
    val (inbox, _) = inboxWithTick()
    inbox.onBlockingPrompt("c1", "scout", "s1", card("r1"))
    inbox.onExpire("r1")
    val row = inbox.pending.value.single()
    assertEquals("r1", row.card.requestId)
    assertTrue(row.expired)
    inbox.onExpire("unknown") // no-op, no crash
    assertEquals(1, inbox.pending.value.size)
  }

  @Test
  fun `remove drops the row`() {
    val (inbox, _) = inboxWithTick()
    inbox.onBlockingPrompt("c1", "scout", "s1", card("r1"))
    inbox.remove("r1")
    assertTrue(inbox.pending.value.isEmpty())
  }

  @Test
  fun `approval respond sends choice and removes on success`() = runBlocking {
    val (inbox, sender) = inboxWithTick()
    inbox.onBlockingPrompt("c1", "scout", "s1", card("r1"))
    val row = inbox.pending.value.single()
    inbox.respond(row, "once")
    val call = sender.calls.single()
    assertEquals("c1", call.first)
    assertEquals("approval.respond", call.second)
    assertEquals("r1", call.third["request_id"]!!.jsonPrimitive.content)
    assertEquals("once", call.third["choice"]!!.jsonPrimitive.content)
    assertEquals("s1", call.third["session_id"]!!.jsonPrimitive.content)
    assertTrue(inbox.pending.value.isEmpty())
  }

  @Test
  fun `clarify respond mirrors the clarify payload with answer`() = runBlocking {
    val (inbox, sender) = inboxWithTick()
    inbox.onBlockingPrompt("c1", "scout", "s1", card("r1", kind = "clarify.request", command = "Proceed?"))
    val row = inbox.pending.value.single()
    inbox.respond(row, "yes")
    val call = sender.calls.single()
    assertEquals("clarify.respond", call.second)
    assertEquals("yes", call.third["answer"]!!.jsonPrimitive.content)
    assertEquals("r1", call.third["request_id"]!!.jsonPrimitive.content)
    assertTrue(inbox.pending.value.isEmpty())
  }

  @Test
  fun `respond failure keeps the entry retryable`() = runBlocking {
    val (inbox, sender) = inboxWithTick()
    sender.fail = true
    inbox.onBlockingPrompt("c1", "scout", "s1", card("r1"))
    val row = inbox.pending.value.single()
    try {
      inbox.respond(row, "once")
      org.junit.Assert.fail("expected IOException")
    } catch (_: IOException) {
    }
    assertEquals(1, inbox.pending.value.size)
    assertFalse(inbox.pending.value.single().expired)
  }

  @Test
  fun `blank session id omits session_id param`() = runBlocking {
    val (inbox, sender) = inboxWithTick()
    inbox.onBlockingPrompt("c1", botName = null, sessionId = null, card = card("r1"))
    val row = inbox.pending.value.single()
    assertNotNull(row)
    inbox.respond(row, "deny")
    assertFalse(sender.calls.single().third.containsKey("session_id"))
  }
}
