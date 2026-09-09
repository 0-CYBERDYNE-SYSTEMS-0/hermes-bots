package ai.hermes.bots.data

import ai.hermes.bots.protocol.GatewayAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetConfigCodecTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun record(
        label: String,
        url: String,
        auth: GatewayAuth = GatewayAuth.TokenAuth("tok"),
        id: String = "id-$label",
    ) = ConnectionRecord(id = id, label = label, baseUrl = url, auth = auth)

    @Test
    fun `export envelope carries all connections with secrets`() {
        val text = FleetConfigCodec.export(
            listOf(
                record("Local", "http://127.0.0.1:9119", GatewayAuth.TokenAuth("dev-9119")),
                record("m1", "http://100.64.0.10:9300", GatewayAuth.BasicAuth("user", "pw123")),
            ),
        )
        val obj = json.parseToJsonElement(text).jsonObject
        assertEquals("hermes-bots", obj["app"]!!.jsonPrimitive.content)
        val gateways = obj["gateways"]!!.jsonArray
        assertEquals(2, gateways.size)
        val m1 = gateways[1].jsonObject
        assertEquals("m1", m1["name"]!!.jsonPrimitive.content)
        assertEquals("user", m1["user"]!!.jsonPrimitive.content)
        assertEquals("pw123", m1["password"]!!.jsonPrimitive.content)
        val local = gateways[0].jsonObject
        assertEquals("dev-9119", local["token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `import round-trips export`() {
        val original = listOf(
            record("Local", "http://127.0.0.1:9119", GatewayAuth.TokenAuth("dev-9119")),
            record("m1", "http://100.64.0.10:9300", GatewayAuth.BasicAuth("user", "pw123")),
        )
        val incoming = FleetConfigCodec.toRecords(FleetConfigCodec.parse(FleetConfigCodec.export(original)))
        assertEquals(2, incoming.size)
        val local = incoming.first { it.label == "Local" }
        assertEquals("http://127.0.0.1:9119", local.baseUrl)
        assertEquals(GatewayAuth.TokenAuth("dev-9119"), local.auth)
        val m1 = incoming.first { it.label == "m1" }
        assertEquals(GatewayAuth.BasicAuth("user", "pw123"), m1.auth)
    }

    @Test
    fun `import accepts bare array`() {
        val entries = FleetConfigCodec.parse(
            """[{"name":"gw","url":"10.0.0.9:9300","token":"t"}]""",
        )
        assertEquals(1, entries.size)
        assertEquals("http://10.0.0.9:9300", entries[0].url)
    }

    @Test
    fun `import defaults blank name to url host`() {
        val entries = FleetConfigCodec.parse(
            """{"gateways":[{"name":"","url":"http://100.64.0.11:9300","user":"w","password":"p"}]}""",
        )
        assertEquals("100.64.0.11", entries[0].name)
    }

    @Test
    fun `user present maps to basic auth even without password`() {
        val records = FleetConfigCodec.toRecords(FleetConfigCodec.parse("""{"gateways":[{"name":"g","url":"http://x:1","user":"u"}]}"""))
        assertEquals(GatewayAuth.BasicAuth("u", ""), records[0].auth)
    }

    @Test
    fun `invalid json fails with a humane message`() {
        assertFails("That isn't valid JSON") { FleetConfigCodec.parse("not json") }
        assertFails("Nothing to import") { FleetConfigCodec.parse("   ") }
        assertFails("No gateways found") { FleetConfigCodec.parse("""{"hello":1}""") }
        assertFails("has no address") { FleetConfigCodec.parse("""{"gateways":[{"name":"x"}]}""") }
    }

    private fun assertFails(fragment: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            assertTrue("message should contain '$fragment': ${e.message}", e.message!!.contains(fragment))
            return
        }
        error("expected IllegalArgumentException containing '$fragment'")
    }

    // --- merge/dedupe -----------------------------------------------------------------------

    @Test
    fun `merge skips exact duplicate by normalized url`() {
        val existing = listOf(record("Local", "http://127.0.0.1:9119"))
        val incoming = listOf(
            record("Local again", "127.0.0.1:9119/"), // same gateway, sloppier form
            record("m1", "http://100.64.0.10:9300"),
        )
        val (toAdd, result) = FleetConfigCodec.merge(existing, incoming)
        assertEquals(1, toAdd.size)
        assertEquals("m1", toAdd[0].label)
        assertEquals(FleetMergeResult(added = 1, skipped = 1), result)
    }

    @Test
    fun `merge dedupes within the incoming batch too`() {
        val (toAdd, result) = FleetConfigCodec.merge(
            emptyList(),
            listOf(
                record("a", "http://x:1"),
                record("b", "http://x:1/"),
            ),
        )
        assertEquals(1, toAdd.size)
        assertEquals(1, result.skipped)
        assertEquals(1, result.added)
    }
}
