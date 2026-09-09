package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayLogicTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `relay agent params carry identity fields`() {
        val agent = RelayEngine.RelayAgent("scout", "scout", "c2", "Remote GW", "Scout", "helper")
        val p = agent.toParams()
        assertEquals("scout", p["profile"]!!.jsonPrimitive.content)
        assertEquals("c2", p["connection_id"]!!.jsonPrimitive.content)
        assertEquals("Remote GW", p["connection_label"]!!.jsonPrimitive.content)
        assertEquals("Scout", p["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `envelope fields read from drain payload`() {
        val drain = json.parseToJsonElement(
            """{"envelopes":[{"id":"e1","message":"ping scout","target_connection":"c2","target_profile":"scout"}]}""",
        ).jsonObject
        val envelopes = drain["envelopes"]!!.jsonArray
        val e = envelopes[0].jsonObject
        assertEquals("e1", e["id"]!!.jsonPrimitive.content)
        assertEquals("c2", e["target_connection"]!!.jsonPrimitive.content)
        assertEquals("scout", e["target_profile"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reply params carry id and reason or reply`() {
        val ok = kotlinx.serialization.json.buildJsonObject {
            put("id", "e1"); put("reply", "done")
        }
        assertEquals("done", ok["reply"]!!.jsonPrimitive.content)
        val fail = kotlinx.serialization.json.buildJsonObject {
            put("id", "e1"); put("reason", "deliver failed (4091): busy")
        }
        assertTrue(fail["reply"] == null)
        assertTrue(fail["reason"]!!.jsonPrimitive.content.contains("4091"))
    }

    private fun agent(handle: String) = RelayEngine.RelayAgent(handle, handle, "c", "GW", handle, "")

    @Test
    fun `transient empty roster fetch keeps cached peers`() {
        val cached = listOf(agent("scout"), agent("default"))
        assertEquals(cached, RelayEngine.mergeAgents(cached, emptyList()))
    }

    @Test
    fun `fresh non-empty fetch replaces cache and empty start accepts empty`() {
        val cached = listOf(agent("scout"))
        val fresh = listOf(agent("recon"), agent("default"))
        assertEquals(fresh, RelayEngine.mergeAgents(cached, fresh))
        assertTrue(RelayEngine.mergeAgents(null, emptyList()).isEmpty())
    }
}
