package ai.hermes.bots.protocol

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * B6: full verification probe against a simulated gateway (MockWebServer REST + WS upgrade).
 * The fake gateway speaks the real framing: gateway.ready push first, JSON-RPC responses by id.
 */
class FleetProbeTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val budgets = FleetProbe.Budgets(statusMs = 2_000, ticketMs = 2_000, wsMs = 3_000, capsMs = 3_000)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    /** Fake gateway socket: ready push, then per-method RPC answers. */
    private class FakeGateway(
        private val relayResult: String,
        private val groupsResult: String,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready",""" +
                    """"payload":{"skin":{},"change_events":true,"heartbeat":true,"replay_epoch":"EPOCH-1"}}}""",
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JsonRpc.decode(text) as? RpcServerRequest ?: return
            val id = when (val v = frame.id) {
                is RpcId.Num -> v.value.toString()
                is RpcId.Str -> "\"${v.value}\""
            }
            val payload = when (frame.method) {
                Catalog.METHOD_BOT_RELAY_ROSTER_SYNC -> relayResult
                Catalog.METHOD_GROUPS_CAPABILITIES -> groupsResult
                else -> """{"code":-32601,"message":"unknown method"}"""
            }
            val kind = if (payload.startsWith("{") && payload.contains("\"code\"")) "error" else "result"
            webSocket.send("""{"jsonrpc":"2.0","id":$id,"$kind":$payload}""")
        }
    }

    @Test
    fun `token mode full chain verifies with groups and relay bits`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"auth_required":false,"version":"0.21.0"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                FakeGateway(
                    relayResult = """{"count":0}""",
                    groupsResult = """{"protocol_version":"1","driver":true}""",
                ),
            ),
        )
        val result = FleetProbe.run(client, baseUrl(), GatewayAuth.TokenAuth("tok"), budgets)
        assertTrue(result.verified)
        assertTrue(result.reachable)
        assertTrue(result.signedIn)
        assertEquals("0.21.0", result.serverVersion)
        assertEquals("EPOCH-1", result.replayEpoch)
        assertEquals(true, result.groupsSupported)
        assertEquals(true, result.relaySupported)
        assertNull(result.failure)
    }

    @Test
    fun `missing relay rpc reports relay off but still verifies`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"auth_required":false,"version":"0.21.0"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                FakeGateway(
                    relayResult = """{"code":-32601,"message":"unknown method"}""",
                    groupsResult = """{"protocol_version":"1"}""",
                ),
            ),
        )
        val result = FleetProbe.run(client, baseUrl(), GatewayAuth.TokenAuth("tok"), budgets)
        assertTrue(result.verified)
        assertEquals(false, result.relaySupported)
        assertEquals(true, result.groupsSupported)
    }

    @Test
    fun `gated mode logs in mints ticket and upgrades with it`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"auth_required":true,"version":"0.21.0"}"""))
        // mintTicket: first call 401 (no cookie yet) → providers → password-login → ticket.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"no_cookie"}"""))
        server.enqueue(MockResponse().setBody(
            """{"providers":[{"name":"basic","display_name":"Basic","supports_password":true}]}""",
        ))
        server.enqueue(MockResponse().setBody("""{"ok":true,"next":"/"}"""))
        server.enqueue(MockResponse().setBody("""{"ticket":"TICKET-9"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                FakeGateway(
                    relayResult = """{"count":0}""",
                    groupsResult = """{"protocol_version":"1"}""",
                ),
            ),
        )
        val result = FleetProbe.run(client, baseUrl(), GatewayAuth.BasicAuth("user", "pw"), budgets)
        assertTrue(result.verified)
        assertTrue(result.signedIn)
        assertEquals("EPOCH-1", result.replayEpoch)
        // The gated flow minted a ticket (cookie login + retry) before the WS upgrade —
        // the WS leg rides the ticket, never a token param (PROTOCOL.md §2).
        assertEquals("/api/status", server.takeRequest().path)
        assertEquals("/api/auth/ws-ticket", server.takeRequest().path) // 401, no cookie yet
        assertEquals("/api/auth/providers", server.takeRequest().path)
        assertEquals("/auth/password-login", server.takeRequest().path)
        assertEquals("/api/auth/ws-ticket", server.takeRequest().path) // retry with session
    }

    @Test
    fun `unreachable gateway fails fast with humane copy`() = runBlocking {
        val dead = ServerSocket(0)
        val port = dead.localPort
        dead.close()
        val result = FleetProbe.run(client, "http://127.0.0.1:$port", GatewayAuth.TokenAuth("t"), budgets)
        assertFalse(result.verified)
        assertFalse(result.reachable)
        assertEquals("Couldn't reach the gateway — check the address.", result.failure)
    }

    @Test
    fun `ws leg that never readies reports the chat channel silently`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"auth_required":false,"version":"0.21.0"}"""))
        // Not a WS upgrade: the handshake leg will keep failing until its budget runs out.
        server.enqueue(MockResponse().setBody("not a websocket"))
        val tight = budgets.copy(wsMs = 700)
        val result = FleetProbe.run(client, baseUrl(), GatewayAuth.TokenAuth("tok"), tight)
        assertFalse(result.verified)
        assertTrue(result.reachable)
        assertEquals("Reached the gateway, but the chat channel didn't answer.", result.failure)
    }

    @Test
    fun `capability decision maps errors to bits`() {
        assertEquals(true, FleetProbe.capabilityFromException(null))
        assertEquals(
            false,
            FleetProbe.capabilityFromException(RpcException(RpcError(Catalog.ERR_METHOD_NOT_FOUND, "unknown"))),
        )
        assertNull(
            FleetProbe.capabilityFromException(RpcException(RpcError(Catalog.ERR_HANDLER, "boom"))),
        )
        assertNull(FleetProbe.capabilityFromException(java.io.IOException("socket closed")))
    }

    @Test
    fun `status failure copy stays humane`() {
        assertEquals(
            "Couldn't reach the gateway — check the address.",
            FleetProbe.statusFailure(GatewayProbe(reachable = false, error = "timeout")),
        )
        assertEquals(
            "That address answered, but it doesn't look like a Hermes gateway.",
            FleetProbe.statusFailure(GatewayProbe(reachable = true, httpCode = 404, error = "status 404")),
        )
        assertNull(FleetProbe.statusFailure(GatewayProbe(reachable = true, authRequired = true)))
        assertNotNull(FleetProbe.statusFailure(GatewayProbe(reachable = false)))
    }
}
