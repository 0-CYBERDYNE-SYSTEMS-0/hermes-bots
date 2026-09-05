package ai.hermes.bots.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same behavior as [org.junit.Assert.fail] but typed as [Nothing] so `?: fail(...)` smart-casts. */
private fun fail(message: String): Nothing = throw AssertionError(message)

class JsonRpcTest {

    @Test
    fun `encode produces exact envelope with params always an object`() {
        val encoded = JsonRpc.encodeRequest(RpcId.Num(7), "gateway.ping", JsonObject(emptyMap()))
        val frame = JsonRpc.decode(encoded) as? RpcServerRequest ?: fail("expected server request shape")
        assertEquals(RpcId.Num(7), frame.id)
        assertEquals("gateway.ping", frame.method)
        assertTrue(encoded.contains("\"params\":{}"))
        assertTrue(encoded.contains("\"jsonrpc\":\"2.0\""))
    }

    @Test
    fun `round trip with object params`() {
        val params = kotlinx.serialization.json.buildJsonObject {
            put("session_id", "s1")
            put("text", "hello")
        }
        val encoded = JsonRpc.encodeRequest(RpcId.Num(1), "prompt.submit", params)
        val frame = JsonRpc.decode(encoded) as RpcServerRequest
        assertEquals("s1", frame.params["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `decode response with result`() {
        val frame = JsonRpc.decode("""{"jsonrpc":"2.0","id":7,"result":{"ok":true}}""") as? RpcResponse
            ?: fail("expected response")
        assertEquals(RpcId.Num(7), frame.id)
        assertEquals(true, frame.result!!.jsonObject["ok"]!!.jsonPrimitive.content.toBooleanStrict())
        assertNull(frame.error)
    }

    @Test
    fun `decode response with string id and error`() {
        val text = """{"jsonrpc":"2.0","id":"h1","error":{"code":-32601,"message":"unknown method","data":{"x":1}}}"""
        val frame = JsonRpc.decode(text) as? RpcResponse ?: fail("expected response")
        assertEquals(RpcId.Str("h1"), frame.id)
        assertEquals(-32601, frame.error!!.code)
        assertEquals("unknown method", frame.error!!.message)
        assertNull(frame.result)
    }

    @Test
    fun `decode push notification without id`() {
        val text = """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","seq":3,"payload":{"text":"hi"}}}"""
        val frame = JsonRpc.decode(text) as? RpcPush ?: fail("expected push")
        assertEquals("event", frame.method)
        assertEquals("message.delta", frame.params["type"]!!.jsonPrimitive.content)
        assertEquals("s1", frame.params["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `decode missing params is empty object`() {
        val text = """{"jsonrpc":"2.0","method":"event","params":{}}"""
        val frame = JsonRpc.decode(text) as RpcPush
        assertTrue(frame.params.isEmpty())
    }

    @Test
    fun `decode garbage throws ProtocolException`() {
        try {
            JsonRpc.decode("not json")
            fail("expected ProtocolException")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.startsWith("unparseable"))
        }
    }

    @Test
    fun `decode frame without method or id throws`() {
        try {
            JsonRpc.decode("""{"jsonrpc":"2.0"}""")
            fail("expected ProtocolException")
        } catch (e: ProtocolException) {
            assertEquals("frame has neither method nor id", e.message)
        }
    }

    @Test
    fun `rpc exception reports method not found`() {
        val e = RpcException(RpcError(Catalog.ERR_METHOD_NOT_FOUND, "nope"))
        assertTrue(e.isMethodNotFound())
    }
}
