package ai.hermes.bots.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val READY_FRAME =
    """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-1"}}}"""

private class RpcServer(
    private val wsRef: AtomicReference<WebSocket?>,
    private val onMessageHandler: (String) -> List<String>,
) : WebSocketListener() {
    override fun onOpen(ws: WebSocket, response: Response) {
        wsRef.set(ws)
        ws.send(READY_FRAME)
    }

    override fun onMessage(ws: WebSocket, text: String) {
        onMessageHandler(text).forEach { ws.send(it) }
    }
}

class HermesGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val client = OkHttpClient()
    private val wsRef = AtomicReference<WebSocket?>(null)
    private lateinit var socket: HermesSocket
    private lateinit var gateway: HermesGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val base = server.url("/").toString().removeSuffix("/")
        socket = HermesSocket(
            client = client,
            scope = scope,
            urlProvider = { "$base/api/ws?token=t" },
            heartbeatIntervalMs = 15_000,
            heartbeatTimeoutMs = 45_000,
            backoffMinMs = 100,
            backoffMaxMs = 400,
        )
        gateway = HermesGateway(socket, scope)
        gateway.start()
        socket.start()
    }

    @After
    fun tearDown() {
        gateway.stop()
        socket.stop()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun failHard(message: String): Nothing = throw AssertionError(message)

    private fun awaitReady() {
        runBlocking {
            withTimeout(5_000) { socket.state.first { it is SocketState.Ready } }
        }
    }

    private fun until(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        if (!cond()) throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private fun idOf(text: String): String =
        """"id":(\d+)""".toRegex().find(text)!!.groupValues[1]

    private fun resp(id: String): String =
        """{"jsonrpc":"2.0","id":$id,"result":{"echo":"$id"}}"""

    private fun push(sid: String, seq: Long, type: String): String =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$sid","seq":$seq,"payload":{"text":"x"}}}"""

    @Test
    fun `request matches response by id`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                RpcServer(wsRef) { text -> listOf(resp(idOf(text))) },
            ),
        )
        awaitReady()
        val result = runBlocking {
            gateway.request("profiles.list", JsonObject(emptyMap()))
        }
        assertEquals("1", result["echo"]!!.jsonPrimitive.content)
    }

    @Test
    fun `out of order responses match by id`() {
        val seen = ConcurrentLinkedQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                RpcServer(wsRef) { text ->
                    seen.add(idOf(text))
                    val ids = seen.toList()
                    if (ids.size >= 2) listOf(resp(ids[1]), resp(ids[0])) else emptyList()
                },
            ),
        )
        awaitReady()
        runBlocking {
            withTimeout(5_000) {
                val r1 = scope.async { gateway.request("m.one", buildJsonObject { put("k", 1) }) }
                Thread.sleep(50)
                val r2 = scope.async { gateway.request("m.two", buildJsonObject { put("k", 2) }) }
                assertEquals("1", r1.await()["echo"]!!.jsonPrimitive.content)
                assertEquals("2", r2.await()["echo"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `error response throws RpcException`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                RpcServer(wsRef) { text ->
                    val id = idOf(text)
                    listOf(
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"unknown method"}}""",
                    )
                },
            ),
        )
        awaitReady()
        runBlocking {
            try {
                gateway.request("nope.method", JsonObject(emptyMap()))
                failHard("expected RpcException")
            } catch (e: RpcException) {
                assertEquals(-32601, e.code)
                assertTrue(e.isMethodNotFound())
            }
        }
    }

    @Test
    fun `push events parsed watermarked and older seq ignored`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(RpcServer(wsRef) { emptyList() }))
        awaitReady()
        val collected = mutableListOf<GatewayEvent>()
        val subscribed = AtomicBoolean(false)
        val job = scope.launch {
            gateway.events
                .onSubscription { subscribed.set(true) }
                .collect { collected.add(it) }
        }
        until { subscribed.get() }
        wsRef.get()!!.send(push("s1", 5, "message.delta"))
        wsRef.get()!!.send(push("s1", 3, "message.delta"))
        until { collected.size >= 2 }
        until { gateway.watermarks.value["s1"] == 5L }
        Thread.sleep(100)
        assertEquals(5L, gateway.watermarks.value["s1"])
        assertEquals("message.delta", collected[0].type)
        assertEquals("s1", collected[0].sessionId)
        assertEquals(5L, collected[0].seq)
        job.cancel()
    }

    @Test
    fun `since parses catchup result`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                RpcServer(wsRef) { text ->
                    if ("session.events.since" in text) {
                        val id = idOf(text)
                        listOf(
                            """{"jsonrpc":"2.0","id":$id,"result":{"events":[{"type":"message.delta","session_id":"s1","seq":5,"payload":{"text":"a"}},{"type":"message.complete","session_id":"s1","seq":7,"payload":{"text":"b"}}],"latest_seq":7,"truncated":false,"epoch":"epoch-1"}}""",
                        )
                    } else {
                        emptyList()
                    }
                },
            ),
        )
        awaitReady()
        val catchUp = runBlocking { gateway.since("s1", 0) }
        assertEquals(2, catchUp.events.size)
        assertEquals("message.delta", catchUp.events[0].type)
        assertEquals("s1", catchUp.events[0].sessionId)
        assertEquals(5L, catchUp.events[0].seq)
        assertEquals(7L, catchUp.latestSeq)
        assertEquals(false, catchUp.truncated)
        assertEquals("epoch-1", catchUp.epoch)
    }

    @Test
    fun `epoch change resets watermarks and bumps generation`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(RpcServer(wsRef) { emptyList() }))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        wsRef.set(ws)
                        ws.send(
                            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-2"}}}""",
                        )
                    }
                },
            ),
        )
        awaitReady()
        wsRef.get()!!.send(push("s1", 5, "message.delta"))
        until { gateway.watermarks.value["s1"] == 5L }
        val genBefore = gateway.epochGeneration.value
        wsRef.get()!!.close(1001, "server restart")
        until { gateway.replayEpoch == "epoch-2" }
        assertEquals(emptyMap<String, Long>(), gateway.watermarks.value)
        assertTrue(gateway.epochGeneration.value > genBefore)
    }

    @Test
    fun `request when not ready throws GatewayNotReadyException`() = runBlocking {
        val idleSocket = HermesSocket(client = client, scope = scope, urlProvider = { "ws://127.0.0.1:1/api/ws" })
        val gw = HermesGateway(idleSocket, scope)
        try {
            gw.request("profiles.list", JsonObject(emptyMap()))
            failHard("expected GatewayNotReadyException")
        } catch (e: GatewayNotReadyException) {
            assertTrue(e.socketState is SocketState.Idle)
        } finally {
            gw.stop()
        }
    }
}
