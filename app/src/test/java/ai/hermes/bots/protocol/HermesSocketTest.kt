package ai.hermes.bots.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val READY_FRAME =
    """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-1"}}}"""

private class ServerWs(
    private val onOpenSend: String? = READY_FRAME,
    private val replyToPing: Boolean = true,
    private val pingCount: AtomicInteger? = null,
    private val wsRef: AtomicReference<WebSocket?>? = null,
) : WebSocketListener() {
    override fun onOpen(ws: WebSocket, response: Response) {
        wsRef?.set(ws)
        onOpenSend?.let { ws.send(it) }
    }

    override fun onMessage(ws: WebSocket, text: String) {
        if (text.contains("gateway.ping")) {
            pingCount?.incrementAndGet()
            if (replyToPing) ws.send("""{"jsonrpc":"2.0","id":"h1","result":{"ok":true}}""")
        }
    }
}

class HermesSocketTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun newSocket(interval: Long, timeout: Long): HermesSocket {
        val base = server.url("/").toString().removeSuffix("/")
        return HermesSocket(
            client = client,
            scope = scope,
            urlProvider = { "$base/api/ws?token=test" },
            heartbeatIntervalMs = interval,
            heartbeatTimeoutMs = timeout,
            backoffMinMs = 100,
            backoffMaxMs = 400,
        )
    }

    private fun awaitState(socket: HermesSocket, predicate: (SocketState) -> Boolean): SocketState =
        runBlocking {
            withTimeout(5_000) { socket.state.first { predicate(it) } }
        }

    private fun awaitReady(socket: HermesSocket): SocketState.Ready =
        awaitState(socket) { it is SocketState.Ready } as SocketState.Ready

    private fun until(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        if (!cond()) throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `first frame gateway ready captures replay epoch`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerWs()))
        val socket = newSocket(15_000, 45_000)
        socket.start()
        val ready = awaitReady(socket)
        assertEquals("epoch-1", ready.replayEpoch)
        socket.stop()
    }

    @Test
    fun `push frames after ready are emitted`() {
        val wsRef = AtomicReference<WebSocket?>(null)
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerWs(wsRef = wsRef)))
        val socket = newSocket(15_000, 45_000)
        socket.start()
        awaitReady(socket)
        val collected = AtomicReference<RpcPush?>(null)
        val subscribed = AtomicBoolean(false)
        val job = scope.launch {
            socket.frames
                .onSubscription { subscribed.set(true) }
                .first { it is RpcPush }
                .let { collected.set(it as RpcPush) }
        }
        until { subscribed.get() }
        wsRef.get()!!.send(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","seq":1,"payload":{"text":"hi"}}}""",
        )
        until { collected.get() != null }
        assertEquals(
            "message.delta",
            (collected.get()!!.params["type"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        job.cancel()
        socket.stop()
    }

    @Test
    fun `heartbeat pings are sent and acked keeps ready`() {
        val pings = AtomicInteger(0)
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerWs(pingCount = pings)))
        val socket = newSocket(100, 500)
        socket.start()
        awaitReady(socket)
        until { pings.get() >= 2 }
        assertTrue(socket.state.value is SocketState.Ready)
        socket.stop()
    }

    @Test
    fun `silent server triggers heartbeat timeout reconnect`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerWs(replyToPing = false)))
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerWs()))
        val socket = newSocket(80, 250)
        socket.start()
        awaitReady(socket)
        until { server.requestCount >= 2 }
        awaitReady(socket)
        socket.stop()
    }

    @Test
    fun `non ready first frame fails then reconnects`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(ServerWs(onOpenSend = """{"jsonrpc":"2.0","id":1,"result":{}}""")),
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerWs()))
        val socket = newSocket(15_000, 45_000)
        socket.start()
        awaitState(socket) { it is SocketState.Disconnected && it.detail.contains("gateway.ready") }
        until { server.requestCount >= 2 }
        socket.stop()
    }

    @Test
    fun `peer graceful close triggers reconnect`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        ws.send(READY_FRAME)
                        ws.close(1001, "server restart")
                    }
                },
            ),
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerWs()))
        val socket = newSocket(15_000, 45_000)
        socket.start()
        awaitReady(socket)
        until { server.requestCount >= 2 }
        awaitReady(socket)
        socket.stop()
    }

    @Test
    fun `stop keeps socket idle without reconnect`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(ServerWs()))
        val socket = newSocket(15_000, 45_000)
        socket.start()
        awaitReady(socket)
        socket.stop()
        assertEquals(SocketState.Idle, socket.state.value)
        Thread.sleep(400)
        assertEquals(1, server.requestCount)
    }
}
