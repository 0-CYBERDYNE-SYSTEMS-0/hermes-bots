package ai.hermes.bots.protocol

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class AuthTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

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

    private fun failHard(message: String): Nothing = throw AssertionError(message)

    @Test
    fun `normalize adds scheme and default port`() {
        assertEquals("http://127.0.0.1:9119", Auth.normalizeBaseUrl("127.0.0.1:9119"))
        assertEquals("http://10.0.2.2:9119", Auth.normalizeBaseUrl("10.0.2.2"))
        assertEquals("http://mac.tail1234.ts.net:9119", Auth.normalizeBaseUrl("mac.tail1234.ts.net"))
        assertEquals("https://mac.ts.net:9119", Auth.normalizeBaseUrl("https://mac.ts.net"))
        assertEquals("http://10.0.2.2:9119", Auth.normalizeBaseUrl("http://10.0.2.2:9119/"))
    }

    @Test
    fun `ws url upgrades scheme and appends api ws path`() {
        assertEquals("ws://127.0.0.1:9119/api/ws", Auth.wsUrl("http://127.0.0.1:9119"))
        assertEquals("wss://mac.ts.net:9119/api/ws", Auth.wsUrl("https://mac.ts.net:9119"))
    }

    @Test
    fun `ws url encodes token and ticket query params`() {
        assertEquals("ws://h:1/api/ws?token=a%20b%26c", Auth.wsUrlWithAuth("http://h:1", "a b&c"))
        assertEquals("ws://h:1/api/ws?ticket=t%2F1", Auth.wsUrlWithTicket("http://h:1", "t/1"))
    }

    @Test
    fun `probe parses auth_required and version`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"auth_required":false,"version":"0.21.0"}"""))
        val probe = Auth.probe(client, baseUrl())
        assertTrue(probe.reachable)
        assertEquals(false, probe.authRequired)
        assertTrue(probe.tokenMode)
        assertEquals("0.21.0", probe.version)
    }

    @Test
    fun `probe unreachable yields reachable false`() = runBlocking {
        val probe = Auth.probe(client, "http://127.0.0.1:1")
        assertFalse(probe.reachable)
        assertTrue(probe.error != null)
    }

    @Test
    fun `mintTicket sends basic auth and parses ticket`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ticket":"TICKET-1"}"""))
        val ticket = Auth.mintTicket(client, baseUrl(), GatewayAuth.BasicAuth("u", "p"))
        assertEquals("TICKET-1", ticket)
        val req = server.takeRequest()
        assertEquals("/api/auth/ws-ticket", req.path)
        val expected = Base64.getEncoder().encodeToString("u:p".toByteArray())
        assertEquals("Basic $expected", req.getHeader("Authorization"))
    }

    @Test
    fun `mintTicket relogs in after 401 and retries`() = runBlocking {
        // Gated gateways reject the first ticket call until the cookie session exists
        // (PROTOCOL.md §2 runtime finding): 401 → providers → password-login → ticket.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"no_cookie"}"""))
        server.enqueue(MockResponse().setBody(
            """{"providers":[{"name":"basic","display_name":"Basic","supports_password":true}]}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"next":"/"}"""))
        server.enqueue(MockResponse().setBody("""{"ticket":"TICKET-2"}"""))
        val ticket = Auth.mintTicket(client, baseUrl(), GatewayAuth.BasicAuth("u", "p"))
        assertEquals("TICKET-2", ticket)
        server.takeRequest() // ticket #1 (401)
        val providersReq = server.takeRequest()
        assertEquals("/api/auth/providers", providersReq.path)
        val loginReq = server.takeRequest()
        assertEquals("/auth/password-login", loginReq.path)
        assertTrue(loginReq.body.readUtf8().contains("\"provider\":\"basic\""))
        val ticketReq = server.takeRequest()
        assertEquals("/api/auth/ws-ticket", ticketReq.path)
    }

    @Test
    fun `mintTicket non-auth error passes through`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        try {
            Auth.mintTicket(client, baseUrl(), GatewayAuth.BasicAuth("u", "p"))
            failHard("expected ProtocolException")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.startsWith("ws-ticket 500"))
        }
    }

    @Test
    fun `restHeaders per auth mode`() {
        assertEquals("tok", Auth.restHeaders(GatewayAuth.TokenAuth("tok"))[Catalog.HEADER_SESSION_TOKEN])
        val basic = Auth.restHeaders(GatewayAuth.BasicAuth("u", "p"))["Authorization"]!!
        assertTrue(basic.startsWith("Basic "))
    }
}
