package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.ui.util.Humanize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import ai.hermes.bots.ui.chat.canDispatchChatSend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.hermes.bots.protocol.RpcError
import ai.hermes.bots.protocol.RpcException

class ChatLogicTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `chat send waits for alignment and releases for no pin timeout or error`() {
        assertFalse(canDispatchChatSend(sessionOpen = true, loading = false, modelAlignmentPending = true))
        assertFalse(canDispatchChatSend(sessionOpen = false, loading = false, modelAlignmentPending = false))
        assertFalse(canDispatchChatSend(sessionOpen = true, loading = true, modelAlignmentPending = false))
        assertTrue(canDispatchChatSend(sessionOpen = true, loading = false, modelAlignmentPending = false))
        assertFalse(
            canDispatchChatSend(sessionOpen = true, loading = false, modelAlignmentPending = false, sending = true),
        )
    }

    @Test
    fun `accepted send clears only the attachment instances it submitted`() {
        val submittedImage = PendingImage("photo.jpg", "AAAA")
        val newlyLoadedImage = PendingImage("photo.jpg", "AAAA")
        val submittedFile = PendingFile("notes.txt", "data:text/plain;base64,QQ==")
        val newlyLoadedFile = PendingFile("notes.txt", "data:text/plain;base64,QQ==")

        assertNull(clearSubmittedAttachment(submittedImage, submittedImage))
        assertSame(newlyLoadedImage, clearSubmittedAttachment(newlyLoadedImage, submittedImage))
        assertNull(clearSubmittedAttachment(submittedFile, submittedFile))
        assertSame(newlyLoadedFile, clearSubmittedAttachment(newlyLoadedFile, submittedFile))
    }

    @Test
    fun `open command detection is exact`() {
        assertTrue(CanonicalChat.isOpenCommand("/new"))
        assertTrue(CanonicalChat.isOpenCommand("  /new  "))
        assertTrue(CanonicalChat.isOpenCommand("/NEW"))
        assertFalse(CanonicalChat.isOpenCommand("/newer"))
        assertFalse(CanonicalChat.isOpenCommand("/new focus"))
        assertFalse(CanonicalChat.isOpenCommand("hello /new"))
    }

    @Test
    fun `slash command parsing preserves arguments and rejects slash prose`() {
        assertEquals(SlashCommand("compress", "focus on the migration"), CanonicalChat.parseSlashCommand(" /COMPRESS focus on the migration "))
        assertEquals(SlashCommand("undo", ""), CanonicalChat.parseSlashCommand("/undo"))
        assertNull(CanonicalChat.parseSlashCommand("explain /compress"))
        assertNull(CanonicalChat.parseSlashCommand("/usr/local/bin"))
        assertTrue(CanonicalChat.isCompressionPreview("focus topic --preview"))
        assertTrue(CanonicalChat.isCompressionPreview("--DRY-RUN keep recent context"))
        assertTrue(CanonicalChat.isCompressionPreview("focus --DryRun"))
        assertFalse(CanonicalChat.isCompressionPreview("preview the migration"))
    }

    @Test
    fun `slash fallback is limited to definite routing errors`() {
        assertTrue(CanonicalChat.shouldFallbackSlashDispatch(RpcException(RpcError(-32601, "method not found"))))
        assertTrue(CanonicalChat.shouldFallbackSlashDispatch(RpcException(RpcError(4018, "skill command: use command.dispatch"))))
        assertFalse(CanonicalChat.shouldFallbackSlashDispatch(RpcException(RpcError(5005, "worker timed out"))))
        assertFalse(CanonicalChat.shouldFallbackSlashDispatch(RpcException(RpcError(4018, "plugin execution failed"))))
    }

    @Test
    fun `slash rpc params match the gateway command contract`() {
        val command = SlashCommand("model", "gpt-5.6")
        val exec = CanonicalChat.slashExecParams("s1", command)
        assertEquals("s1", exec["session_id"]!!.jsonPrimitive.content)
        assertEquals("model gpt-5.6", exec["command"]!!.jsonPrimitive.content)
        val dispatch = CanonicalChat.commandDispatchParams("s1", command)
        assertEquals("s1", dispatch["session_id"]!!.jsonPrimitive.content)
        assertEquals("model", dispatch["name"]!!.jsonPrimitive.content)
        assertEquals("gpt-5.6", dispatch["arg"]!!.jsonPrimitive.content)
    }

    @Test
    fun `slash responses preserve output and typed user actions`() {
        val output = CanonicalChat.parseSlashDirective(
            json.parseToJsonElement("""{"output":"current model","warning":"stale pin"}""").jsonObject,
        ) as SlashDirective.Output
        assertEquals("current model", output.text)
        assertEquals("stale pin", output.warning)
        val warningOnly = CanonicalChat.parseSlashDirective(
            json.parseToJsonElement("""{"warning":"stale pin"}""").jsonObject,
        ) as SlashDirective.Output
        assertEquals("", warningOnly.text)
        assertEquals("stale pin", warningOnly.warning)
        val skill = CanonicalChat.parseSlashDirective(
            json.parseToJsonElement("""{"type":"skill","name":"review","message":"expanded","display":"/review"}""").jsonObject,
        ) as SlashDirective.Submit
        assertEquals("expanded", skill.message)
        assertEquals("/review", skill.display)
        val undo = CanonicalChat.parseSlashDirective(
            json.parseToJsonElement("""{"type":"prefill","message":"corrected prompt"}""").jsonObject,
        ) as SlashDirective.Prefill
        assertEquals("corrected prompt", undo.message)
    }

    @Test
    fun `create params follow canonical convention`() {
        val params = CanonicalChat.createParams("alf")
        assertEquals("Bot Chat", params["title"]!!.jsonPrimitive.content)
        assertEquals("true", params["hidden"]!!.jsonPrimitive.content)
        assertEquals("false", params["close_on_disconnect"]!!.jsonPrimitive.content)
        assertEquals("alf", params["profile"]!!.jsonPrimitive.content)
        assertEquals("true", params["follow_profile_config"]!!.jsonPrimitive.content)
        assertNull(params["model"])
        assertNull(params["provider"])
    }

    @Test
    fun `create params send a registry pin and skip a custom pin`() {
        val registry = CanonicalChat.createParams("scout", "grok-4", "xai")
        assertEquals("grok-4", registry["model"]!!.jsonPrimitive.content)
        assertEquals("xai", registry["provider"]!!.jsonPrimitive.content)
        val custom = CanonicalChat.createParams("scout", "deepseek/v4", "custom:clinepass")
        assertNull(custom["model"])
        assertNull(custom["provider"])
        assertEquals("true", custom["follow_profile_config"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pin mismatch is only a named session model that is not the bot pin`() {
        assertFalse(CanonicalChat.pinMismatch("grok-4", "xai", "grok-4", "xai"))
        assertFalse(CanonicalChat.pinMismatch("", "", "grok-4", "xai"))
        assertFalse(CanonicalChat.pinMismatch("grok-4", "xai", "", "xai"))
        assertTrue(CanonicalChat.pinMismatch("grok-3", "xai", "grok-4", "xai"))
        assertTrue(CanonicalChat.pinMismatch("grok-4", "openrouter", "grok-4", "xai"))
    }

    @Test
    fun `model switch attempts omit consent until the user confirms`() {
        val attempts = CanonicalChat.switchAttempts("sid-1", "scout", "xai", "grok-4")
        assertEquals("grok-4 --provider xai --session", attempts[0]["value"]!!.jsonPrimitive.content)
        assertEquals("scout", attempts[0]["profile"]!!.jsonPrimitive.content)
        assertNull(attempts[0]["confirm_expensive_model"])
        assertNull(attempts[1]["profile"])
        assertEquals("grok-4 --provider xai", attempts[2]["value"]!!.jsonPrimitive.content)
        val confirmed = CanonicalChat.switchAttempts("sid-1", "scout", "xai", "grok-4", confirmExpensiveModel = true)
        assertEquals("true", confirmed[0]["confirm_expensive_model"]!!.jsonPrimitive.content)
        assertTrue(CanonicalChat.isContractMismatch("invalid params for config.set: profile: Extra inputs are not permitted"))
        assertFalse(CanonicalChat.isContractMismatch("rpc 5001: Unknown provider 'xai'"))
        val landed = json.parseToJsonElement("""{"key":"model","confirm_required":false}""").jsonObject
        val deferred = json.parseToJsonElement("""{"key":"model","confirm_required":false,"deferred":true}""").jsonObject
        val blocked = json.parseToJsonElement(
            """{"key":"model","confirm_required":true,"confirm_message":"Expensive model"}""",
        ).jsonObject
        assertTrue(CanonicalChat.switchAccepted(landed))
        assertTrue(CanonicalChat.switchAccepted(deferred))
        assertTrue(CanonicalChat.switchDeferred(deferred))
        assertFalse(CanonicalChat.switchDeferred(landed))
        assertFalse(CanonicalChat.switchAccepted(blocked))
        assertEquals("Expensive model", CanonicalChat.switchDetail(blocked))
    }

    @Test
    fun `align retries when the gateway rejects an extra field`() = runBlocking {
        val seen = mutableListOf<String>()
        val result = CanonicalChat.alignSession("sid", "scout", "openai-codex", "gpt-5.6") { params ->
            val value = params["value"]!!.jsonPrimitive.content
            seen += value + if (params["profile"] != null) "+profile" else ""
            if (params["profile"] != null) {
                throw RpcException(RpcError(4000, "invalid params for config.set: profile: Extra inputs are not permitted"))
            }
            JsonObject(mapOf("key" to json.parseToJsonElement("\"model\"")))
        }
        assertTrue(result.accepted)
        assertEquals("gpt-5.6 --provider openai-codex --session+profile", seen[0])
        assertEquals("gpt-5.6 --provider openai-codex --session", seen[1])
    }

    @Test
    fun `align returns confirmation requirement without retrying or asserting consent`() = runBlocking {
        var requests = 0
        val result = CanonicalChat.alignSession("sid", "scout", "xai", "grok-4") { params ->
            requests++
            assertNull(params["confirm_expensive_model"])
            json.parseToJsonElement("""{"confirm_required":true,"confirm_message":"data policy"}""").jsonObject
        }
        assertFalse(result.accepted)
        assertTrue(result.confirmationRequired)
        assertEquals("data policy", result.detail)
        assertEquals(1, requests)
    }

    @Test
    fun `align preserves an accepted deferred switch separately from an applied switch`() = runBlocking {
        val result = CanonicalChat.alignSession("sid", "scout", "openai", "gpt-5") {
            json.parseToJsonElement("""{"key":"model","deferred":true}""").jsonObject
        }
        assertTrue(result.accepted)
        assertTrue(result.deferred)
        assertFalse(result.confirmationRequired)
    }

    @Test
    fun `align stops after an unknown provider error`() = runBlocking {
        var requests = 0
        val result = CanonicalChat.alignSession("sid", "scout", "xai", "grok-4") {
            requests++
            throw RpcException(RpcError(5001, "Unknown provider 'xai'"))
        }
        assertFalse(result.accepted)
        assertEquals(1, requests)
    }

    @Test
    fun `running pin reads model and provider from session info`() {
        val result = json.parseToJsonElement(
            """{"session_id":"s","info":{"model":"grok-3","provider":"openrouter"}}""",
        ).jsonObject
        assertEquals("grok-3" to "openrouter", CanonicalChat.runningPin(result))
        assertEquals("" to "", CanonicalChat.runningPin(json.parseToJsonElement("""{}""").jsonObject))
    }

    @Test
    fun `submit and compress params carry session id`() {
        assertEquals("s1", CanonicalChat.submitParams("s1", "hi")["session_id"]!!.jsonPrimitive.content)
        assertEquals("hi", CanonicalChat.submitParams("s1", "hi")["text"]!!.jsonPrimitive.content)
        assertEquals("s1", CanonicalChat.compressParams("s1")["session_id"]!!.jsonPrimitive.content)
        assertNull(CanonicalChat.compressParams("s1")["focus_topic"])
        assertEquals("migration", CanonicalChat.compressParams("s1", " migration ")["focus_topic"]!!.jsonPrimitive.content)
    }

    @Test
    fun `compression response distinguishes success lock and no-op`() {
        val success = json.parseToJsonElement(
            """{"compressed":true,"removed":12,"summary":{"headline":"Preserved the deploy plan."}}""",
        ).jsonObject
        assertEquals(
            "Conversation compressed.\nRemoved 12 older messages.\nPreserved the deploy plan.",
            CanonicalChat.compressionFeedback(success),
        )
        val locked = json.parseToJsonElement("""{"compressed":false,"lock_held":true}""").jsonObject
        assertTrue(CanonicalChat.compressionFeedback(locked).contains("already running"))
        val noop = json.parseToJsonElement("""{"compressed":false,"summary":{"noop":true,"note":"Already concise."}}""").jsonObject
        assertEquals("Already concise.", CanonicalChat.compressionFeedback(noop))
        val previewNoop = json.parseToJsonElement(
            """{"compressed":true,"status":"compressed","summary":{"noop":true,"headline":"No compression required."}}""",
        ).jsonObject
        assertEquals("No compression required.", CanonicalChat.compressionFeedback(previewNoop))
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
        // A free-text clarify with no choices shows a reply affordance instead of made-up choices.
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

    @Test
    fun `resume message retains generated image data`() {
        val messages = json.parseToJsonElement(
            """[{"role":"assistant","text":"Here","content":[{"type":"image","data":"data:image/png;base64,AA=="}]}]""",
        ).jsonArray
        assertEquals("data:image/png;base64,AA==", ChatMessagesParser.parse(messages).single().imageDataUrl)
    }

    @Test
    fun `document attach matches the gateway data url contract`() {
        val dataUrl = "data:text/plain;base64,SGk="
        val params = CanonicalChat.fileAttachParams("session-1", dataUrl)
        assertEquals(setOf("session_id", "data"), params.keys)
        assertEquals(dataUrl, params["data"]?.jsonPrimitive?.content)
    }
}

/** A stale-session error triggers the VM's re-open path; ordinary submit failures keep their own handling. */
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
