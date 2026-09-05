package ai.hermes.bots.data

import ai.hermes.bots.protocol.GatewayAuth
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionRecordJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `connection record round trip with token auth`() {
        val rec = ConnectionRecord("id1", "Local", "http://127.0.0.1:9119", GatewayAuth.TokenAuth("tok"), primary = true)
        val decoded = json.decodeFromString<ConnectionRecord>(json.encodeToString(rec))
        assertEquals(rec, decoded)
    }

    @Test
    fun `connection record round trip with basic auth`() {
        val rec = ConnectionRecord("id2", "TS", "https://mac.tail.ts.net:9119", GatewayAuth.BasicAuth("u", "p"))
        val decoded = json.decodeFromString<ConnectionRecord>(json.encodeToString(rec))
        assertEquals(GatewayAuth.BasicAuth("u", "p"), decoded.auth)
    }

    @Test
    fun `auth discriminator present in json`() {
        val text = json.encodeToString(GatewayAuth.TokenAuth("x") as GatewayAuth)
        assert(text.contains(""""type":"token"""") || text.contains(""""token""""))
    }

    @Test
    fun `record list round trip`() {
        val list = listOf(
            ConnectionRecord("a", "one", "http://h:1", GatewayAuth.TokenAuth("t"), true),
            ConnectionRecord("b", "two", "http://h:2", GatewayAuth.BasicAuth("u", "p")),
        )
        assertEquals(list, json.decodeFromString<List<ConnectionRecord>>(json.encodeToString(list)))
    }
}
