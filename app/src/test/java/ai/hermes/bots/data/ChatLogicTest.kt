package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.ui.util.Humanize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `open command detection is exact`() {
        assertTrue(CanonicalChat.isOpenCommand("/new"))
        assertTrue(CanonicalChat.isOpenCommand("  /new  "))
        assertFalse(CanonicalChat.isOpenCommand("/newer"))
        assertFalse(CanonicalChat.isOpenCommand("hello /new"))
    }

    @Test
    fun `create params follow canonical convention`() {
        val params = CanonicalChat.createParams("alf")
        assertEquals("Bot Chat", params["title"]!!.jsonPrimitive.content)
        assertEquals("true", params["hidden"]!!.jsonPrimitive.content)
        assertEquals("false", params["close_on_disconnect"]!!.jsonPrimitive.content)
        assertEquals("alf", params["profile"]!!.jsonPrimitive.content)
    }

    @Test
    fun `submit and compress params carry session id`() {
        assertEquals("s1", CanonicalChat.submitParams("s1", "hi")["session_id"]!!.jsonPrimitive.content)
        assertEquals("hi", CanonicalChat.submitParams("s1", "hi")["text"]!!.jsonPrimitive.content)
        assertEquals("s1", CanonicalChat.compressParams("s1")["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `approval card parses command and choices`() {
        val payload = json.parseToJsonElement(
            """{"request_id":"r1","command":"ls -la","choices":["once","session","deny"]}""",
        ).jsonObject
        val card = CanonicalChat.parseCard(Catalog.EVENT_APPROVAL_REQUEST, payload)!!
        assertEquals("r1", card.requestId)
        assertEquals("ls -la", card.command)
        assertEquals(listOf("once", "session", "deny"), card.choices)
    }

    @Test
    fun `clarify card falls back to question and answers`() {
        val payload = json.parseToJsonElement(
            """{"request_id":"r2","question":"Which one?","answers":["left","right"]}""",
        ).jsonObject
        val card = CanonicalChat.parseCard(Catalog.EVENT_CLARIFY_REQUEST, payload)!!
        assertEquals("Which one?", card.command)
        assertEquals(listOf("left", "right"), card.choices)
    }

    @Test
    fun `card without request id is null and choices are never fabricated`() {
        assertNull(CanonicalChat.parseCard(Catalog.EVENT_APPROVAL_REQUEST, null))
        assertNull(CanonicalChat.parseCard(Catalog.EVENT_APPROVAL_REQUEST, json.parseToJsonElement("{}").jsonObject))
        // Q11 (QA 2026-09-14): a payload with no choices (free-text clarify) keeps empty
        // choices — the UI shows a reply affordance instead of sending a made-up answer.
        val card = CanonicalChat.parseCard(
            Catalog.EVENT_CLARIFY_REQUEST,
            json.parseToJsonElement("""{"request_id":"r3","question":"What next?"}""").jsonObject,
        )
        assertNotNull(card)
        assertTrue(card!!.choices.isEmpty())
        assertEquals("What next?", card.command)
    }

    @Test
    fun `resume messages parse into items`() {
        val messages = json.parseToJsonElement(
            """[
              {"role":"user","text":"hello bot"},
              {"role":"assistant","text":"hi human"},
              {"role":"tool","name":"shell","context":"ran ls"},
              {"role":"system","text":"internal"}
            ]""",
        ).jsonArray
        val items = ChatMessagesParser.parse(messages)
        assertEquals(3, items.size)
        assertEquals(ItemKind.USER, items[0].kind)
        assertEquals("hello bot", items[0].text)
        assertEquals(ItemKind.ASSISTANT, items[1].kind)
        assertEquals(ItemKind.TOOL, items[2].kind)
        assertEquals("shell", items[2].toolName)
        assertEquals("ran ls", items[2].summary)
    }

    @Test
    fun `null messages parse to empty`() {
        assertTrue(ChatMessagesParser.parse(null).isEmpty())
    }
}

/** Zombie-session heal policy (live dogfood 2026-09-16): 4001 after a socket drop must
 * trigger the VM's re-open path, while ordinary submit failures keep their own handling. */
class StaleSessionHealPolicyTest {

  @Test
  fun `rpc 4001 session-not-found is stale`() {
    assertTrue(AnyChatSendRetry.isStaleSessionError(4001, "rpc 4001: session not found"))
    assertTrue(AnyChatSendRetry.isStaleSessionError(null, "Session not found"))
    assertTrue(AnyChatSendRetry.isStaleSessionError(null, "no such session"))
  }

  @Test
  fun `ordinary submit failures are not stale`() {
    assertFalse(AnyChatSendRetry.isStaleSessionError(4091, "session is busy"))
    assertFalse(AnyChatSendRetry.isStaleSessionError(-32602, "invalid params"))
    assertFalse(AnyChatSendRetry.isStaleSessionError(null, "gateway took too long"))
  }

  @Test
  fun `reconnect notice is humane and passes through friendlyError untouched`() {
    val raw = ChatStream.RECONNECT_NOTICE
    assertEquals(raw, Humanize.friendlyError(raw, "profile-architect"))
  }
}
