package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model-pin related parse/params for profiles.create / profiles.configure / model.* RPCs. */
class BotAdminPinTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun result(payload: String) = json.parseToJsonElement(payload).jsonObject

    @Test
    fun `parse create prefers the path's last segment as the canonical id (R3)`() {
        val out = BotAdmin.parseCreate(
            result(
                """{"ok":true,"name":"Validator 2","path":"/home/u/.hermes/profiles/validator-2",
                   "soul_written":true,"model_set":true,"mirrored":{"soul":true}}""",
            ),
        )
        assertTrue(out.ok)
        assertEquals("validator-2", out.name)
        assertTrue(out.modelSet)
        assertEquals("/home/u/.hermes/profiles/validator-2", out.path)
    }

    @Test
    fun `parse create falls back to the echoed name when path is missing`() {
        val out = BotAdmin.parseCreate(
            result("""{"ok":true,"name":"validator"}"""),
        )
        assertTrue(out.ok)
        assertEquals("validator", out.name)
        assertFalse("model_set absent means the pin did not land", out.modelSet)
        assertNull(out.path)
    }

    @Test
    fun `parse create tolerates trailing slash and missing ok`() {
        val out = BotAdmin.parseCreate(
            result("""{"name":"x","path":"/root/profiles/x/"}"""),
        )
        assertTrue("ok absent reads as success", out.ok)
        assertEquals("x", out.name)
    }

    @Test
    fun `parse configure outcome reads applied model (R9)`() {
        val applied = BotAdmin.parseConfigureOutcome(
            result(
                """{"ok":true,"applied":{"model":true,"soul":false},
                   "confirm_required":false,"confirm_message":null}""",
            ),
        )
        assertTrue(applied.ok)
        assertTrue(applied.modelApplied == true)
        assertFalse(applied.confirmRequired)
        assertNull(applied.confirmMessage)

        val notApplied = BotAdmin.parseConfigureOutcome(
            result("""{"ok":true,"applied":{"model":false}}"""),
        )
        assertTrue(notApplied.modelApplied == false)

        val noPin = BotAdmin.parseConfigureOutcome(
            result("""{"ok":true,"applied":{"soul":true}}"""),
        )
        assertNull("no model in the request → applied.model absent", noPin.modelApplied)

        val noApplied = BotAdmin.parseConfigureOutcome(result("""{"ok":true}"""))
        assertNull(noApplied.modelApplied)
    }

    @Test
    fun `parse configure outcome keeps confirm handshake fields`() {
        val out = BotAdmin.parseConfigureOutcome(
            result("""{"ok":false,"confirm_required":true,"confirm_message":"expensive model"}"""),
        )
        assertFalse(out.ok)
        assertTrue(out.confirmRequired)
        assertEquals("expensive model", out.confirmMessage)
    }

    @Test
    fun `model options params ask for unconfigured rows by default`() {
        val p = BotAdmin.modelOptionsParams()
        assertEquals("true", p["include_unconfigured"]!!.jsonPrimitive.content)
        assertNull(p["refresh"])
        val off = BotAdmin.modelOptionsParams(includeUnconfigured = false)
        assertEquals("false", off["include_unconfigured"]!!.jsonPrimitive.content)
        val refresh = BotAdmin.modelOptionsParams(refresh = true)
        assertEquals("true", refresh["refresh"]!!.jsonPrimitive.content)
    }

    @Test
    fun `save key params carry slug and api_key`() {
        val p = BotAdmin.saveKeyParams("openrouter", "test-api-key-value")
        assertEquals("openrouter", p["slug"]!!.jsonPrimitive.content)
        assertEquals("test-api-key-value", p["api_key"]!!.jsonPrimitive.content)
    }
}
