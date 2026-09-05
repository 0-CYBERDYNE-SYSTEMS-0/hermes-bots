package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
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
    fun `card without request id is null and defaults choices`() {
        assertNull(CanonicalChat.parseCard(Catalog.EVENT_APPROVAL_REQUEST, null))
        assertNull(CanonicalChat.parseCard(Catalog.EVENT_APPROVAL_REQUEST, json.parseToJsonElement("{}").jsonObject))
        val card = CanonicalChat.parseCard(
            Catalog.EVENT_APPROVAL_REQUEST,
            json.parseToJsonElement("""{"request_id":"r3"}""").jsonObject,
        )
        assertNotNull(card)
        assertEquals(listOf("once", "deny"), card!!.choices)
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
