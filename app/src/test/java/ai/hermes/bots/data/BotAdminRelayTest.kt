package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3: the editor's unconditional relay-enable write. Server facts this locks in
 * (verified against hermes-agent source):
 *  - `ui_meta["hermes-bots"]` present (even `{}`) flips bot_mode_probe.is_bot_mode_managed,
 *    which is what makes gateways inject `message_agent`;
 *  - profiles.list only includes NON-empty ui_meta (methods_profiles.py), so the create
 *    payload must carry at least one key for the state to be surfaceable;
 *  - configure merges ui_meta key-wise at the TOP level, so the client must merge the
 *    hermes-bots object locally before writing.
 */
class BotAdminRelayTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `create patch always carries a non-empty hermes-bots payload`() {
        // Create with no section and not hidden — the payload the editor writes unconditionally.
        val patch = BotAdmin.mergeUiMeta(null, BotAdmin.UiMetaPatch(sectionId = null, hidden = false))
        val section = patch["hermes-bots"]!!.jsonObject
        assertTrue("payload must be non-empty so profiles.list exposes it", section.isNotEmpty())
        assertEquals("false", section["hidden"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create patch with section keeps both keys`() {
        val patch = BotAdmin.mergeUiMeta(null, BotAdmin.UiMetaPatch(sectionId = "work", hidden = true))
        val section = patch["hermes-bots"]!!.jsonObject
        assertEquals("work", section["sectionId"]!!.jsonPrimitive.content)
        assertEquals("true", section["hidden"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge preserves existing hermes-bots content`() {
        val current = json.parseToJsonElement(
            """{"hermes-bots":{"sectionId":"work","color":"blue"}}""",
        ).jsonObject
        val patch = BotAdmin.mergeUiMeta(current, BotAdmin.UiMetaPatch(hidden = true))
        val section = patch["hermes-bots"]!!.jsonObject
        assertEquals("work", section["sectionId"]!!.jsonPrimitive.content)
        assertEquals("blue", section["color"]!!.jsonPrimitive.content)
        assertEquals("true", section["hidden"]!!.jsonPrimitive.content)
    }

    @Test
    fun `configure params carry ui_meta with CAS revisions`() {
        val patch = BotAdmin.mergeUiMeta(null, BotAdmin.UiMetaPatch(hidden = false))
        val params = BotAdmin.configureParams("scout", uiMeta = patch, expectedRevisions = mapOf("hermes-bots" to 3))
        assertEquals(patch, params["ui_meta"]!!.jsonObject)
        assertEquals("3", params["ui_meta_expected_revisions"]!!.jsonObject["hermes-bots"]!!.jsonPrimitive.content)
        assertNotNull(params["name"])
    }

    @Test
    fun `relayCapable reads ui_meta hermes-bots from a profiles list row`() {
        val capable = json.parseToJsonElement(
            """{"name":"scout","ui_meta":{"hermes-bots":{"hidden":false}},"ui_meta_revisions":{"hermes-bots":1}}""",
        ).jsonObject
        assertTrue(RosterParsing.relayCapable(capable))

        val unflagged = json.parseToJsonElement("""{"name":"default"}""").jsonObject
        assertFalse(RosterParsing.relayCapable(unflagged))

        // ui_meta present but without the hermes-bots key → not relay-capable.
        val otherMeta = json.parseToJsonElement(
            """{"name":"x","ui_meta":{"other-plugin":{"a":1}}}""",
        ).jsonObject
        assertFalse(RosterParsing.relayCapable(otherMeta))
    }
}
