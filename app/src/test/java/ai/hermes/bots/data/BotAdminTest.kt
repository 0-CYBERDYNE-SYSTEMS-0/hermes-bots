package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive

class BotAdminTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `create params carry optional sections`() {
        val p = BotAdmin.createParams("alf", description = "helper", cloneFrom = "default", model = "m1", provider = "p1")
        assertEquals("alf", p["name"]!!.jsonPrimitive.content)
        assertEquals("helper", p["description"]!!.jsonPrimitive.content)
        assertEquals("default", p["clone_from"]!!.jsonPrimitive.content)
        assertEquals("m1", p["model"]!!.jsonPrimitive.content)
        assertEquals("p1", p["provider"]!!.jsonPrimitive.content)
        assertNull(p["soul"])
    }

    @Test
    fun `ui meta merge updates section and hidden in place`() {
        val current = json.parseToJsonElement(
            """{"hermes-bots":{"sectionId":"crew","color":"#123"},"other":1}""",
        ).jsonObject
        val merged = BotAdmin.mergeUiMeta(current, BotAdmin.UiMetaPatch(sectionId = "lounge", hidden = true))
        val section = merged["hermes-bots"]!!.jsonObject
        assertEquals("lounge", section["sectionId"]!!.jsonPrimitive.content)
        assertEquals("true", section["hidden"]!!.jsonPrimitive.content)
        assertEquals("#123", section["color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `configure params include cas revisions`() {
        val uiMeta = BotAdmin.mergeUiMeta(null, BotAdmin.UiMetaPatch(hidden = false))
        val p = BotAdmin.configureParams("alf", uiMeta = uiMeta, expectedRevisions = mapOf("hermes-bots" to 7))
        assertEquals(7, p["ui_meta_expected_revisions"]!!.jsonObject["hermes-bots"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `profile config params carry the bot profile`() {
        val get = BotAdmin.configGetParams("approvals.mode", "scout")
        assertEquals("approvals.mode", get["key"]!!.jsonPrimitive.content)
        assertEquals("scout", get["profile"]!!.jsonPrimitive.content)

        val set = BotAdmin.configSetParams("approvals.mode", JsonPrimitive("off"), "scout")
        assertEquals("approvals.mode", set["key"]!!.jsonPrimitive.content)
        assertEquals("off", set["value"]!!.jsonPrimitive.content)
        assertEquals("scout", set["profile"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prep config parser reads nested settings and allowlist`() {
        val snapshot = json.parseToJsonElement(
            """{"config":{"approvals":{"mode":"off","timeout":900},
               "clarify":{"timeout":0},"command_allowlist":["git status","ls"]}}""",
        ).jsonObject
        val prep = BotAdmin.parsePrepConfig(snapshot)
        assertEquals("off", prep.approvalMode)
        assertEquals(900L, prep.approvalTimeoutSeconds)
        assertEquals(0L, prep.clarifyTimeoutSeconds)
        assertEquals(listOf("git status", "ls"), prep.commandAllowlist)
    }

    @Test
    fun `prep config parser normalizes YAML false approval mode`() {
        val snapshot = json.parseToJsonElement(
            """{"config":{"approvals":{"mode":false}}}""",
        ).jsonObject

        assertEquals("off", BotAdmin.parsePrepConfig(snapshot).approvalMode)
    }

    @Test
    fun `prep config parser rejects incomplete full responses`() {
        val valueOnly = json.parseToJsonElement("""{"value":"smart"}""").jsonObject
        val missingMode = json.parseToJsonElement("""{"config":{}}""").jsonObject
        val nullMode = json.parseToJsonElement("""{"config":{"approvals":{"mode":null}}}""").jsonObject

        assertTrue(runCatching { BotAdmin.parsePrepConfig(valueOnly) }.isFailure)
        assertTrue(runCatching { BotAdmin.parsePrepConfig(missingMode) }.isFailure)
        assertTrue(runCatching { BotAdmin.parsePrepConfig(nullMode) }.isFailure)
    }

    @Test
    fun `describe snapshot parses`() {
        val result = json.parseToJsonElement(
            """{"name":"alf","description":"helper","soul":"Be kind.",
               "model":{"provider":"p1","default":"m1"},
               "skills":[{"name":"search","enabled":true},{"name":"x","enabled":false}],
               "toolsets":[{"name":"core","enabled":true}],
               "mcp_servers":[{"name":"srv","enabled":false,"transport":"stdio"}]}""",
        ).jsonObject
        val snap = BotAdmin.parseDescribe(result)
        assertEquals("alf", snap.name)
        assertEquals("Be kind.", snap.soul)
        assertEquals("p1", snap.modelProvider)
        assertEquals("m1", snap.modelDefault)
        assertEquals(2, snap.skills.size)
        assertFalse(snap.skills[1].enabled)
        assertEquals("stdio", snap.mcpServers[0].transport)
    }

    @Test
    fun `configure result parses confirm`() {
        val r = BotAdmin.parseConfigure(
            json.parseToJsonElement(
                """{"ok":false,"applied":{"soul":true},"confirm_required":true,"confirm_message":"expensive model"}""",
            ).jsonObject,
        )
        assertFalse(r.ok)
        assertTrue(r.confirmRequired)
        assertEquals("expensive model", r.confirmMessage)
    }

    @Test
    fun `avatar data url validation`() {
        val tiny = "data:image/png;base64," + "a".repeat(100)
        assertEquals(tiny, BotAdmin.validateAvatarDataUrl(tiny))
        assertNull(BotAdmin.validateAvatarDataUrl("data:text/plain;base64,aaa"))
        assertNull(BotAdmin.validateAvatarDataUrl("data:image/png;base64," + "a".repeat(3_000_000)))
        assertNotNull(BotAdmin.validateAvatarDataUrl("data:image/webp;base64,aaa"))
    }
}
