package ai.hermes.bots.ui.probe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.hermes.bots.protocol.Auth
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.GatewayProbe
import ai.hermes.bots.protocol.HermesGateway
import ai.hermes.bots.protocol.HermesSocket
import ai.hermes.bots.protocol.SocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

data class ProbeUiState(
    // Dev gateways are reached via `adb reverse tcp:9119 tcp:9119`; the loopback
    // Host header is required by the gateway's host/origin guard (PROTOCOL.md §2).
    val baseUrl: String = "http://127.0.0.1:9119",
    val token: String = "dev-token-9119",
    val probe: GatewayProbe? = null,
    val socketState: SocketState = SocketState.Idle,
    val bots: List<String> = emptyList(),
    val log: List<String> = emptyList(),
)

/** Phase-1 integration harness: probe / connect / raw roster against a real gateway. */
class ProbeViewModel : ViewModel() {
    private val client = OkHttpClient()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: HermesSocket? = null
    private var gateway: HermesGateway? = null

    private val _ui = MutableStateFlow(ProbeUiState())
    val ui: StateFlow<ProbeUiState> = _ui

    fun updateBaseUrl(value: String) = _ui.update { it.copy(baseUrl = value) }
    fun updateToken(value: String) = _ui.update { it.copy(token = value) }

    fun testProbe() {
        viewModelScope.launch {
            val p = Auth.probe(client, Auth.normalizeBaseUrl(_ui.value.baseUrl))
            append("probe: $p")
            _ui.update { it.copy(probe = p) }
        }
    }

    fun connect() {
        disconnect()
        val base = Auth.normalizeBaseUrl(_ui.value.baseUrl)
        val token = _ui.value.token
        val s = HermesSocket(client = client, scope = ioScope, urlProvider = { Auth.wsUrlWithAuth(base, token) })
        val g = HermesGateway(s, ioScope)
        socket = s
        gateway = g
        g.start()
        s.start()
        ioScope.launch {
            g.state.collect { st ->
                _ui.update { it.copy(socketState = st) }
                append("state: $st")
            }
        }
        ioScope.launch {
            g.events.collect { ev ->
                append("event: ${ev.type} sid=${ev.sessionId ?: "-"} seq=${ev.seq ?: "-"}")
            }
        }
    }

    fun fetchRoster() {
        val g = gateway ?: return
        viewModelScope.launch {
            try {
                val result = g.request(
                    Catalog.METHOD_PROFILES_LIST,
                    buildJsonObject { put("include_sessions", true) },
                )
                val rows = (result["profiles"] as? JsonArray)?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    fun str(key: String): String? =
                        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    val name = str("display_name") ?: str("name") ?: "?"
                    val model = str("model") ?: "-"
                    val skills = (o["skill_count"] as? JsonPrimitive)?.content ?: "-"
                    val canonical = o["canonical_session"] as? JsonObject
                    val chatId = (canonical?.get("resolved_id") as? JsonPrimitive)?.content ?: "-"
                    "$name | $model | skills=$skills | chat=$chatId"
                }.orEmpty()
                append("profiles.list -> ${rows.size} rows")
                _ui.update { it.copy(bots = rows) }
            } catch (e: Exception) {
                append("profiles.list FAILED: ${e.message}")
            }
        }
    }

    fun disconnect() {
        gateway?.stop()
        socket?.stop()
        gateway = null
        socket = null
    }

    private fun append(line: String) {
        _ui.update { it.copy(log = (it.log + line).takeLast(100)) }
    }

    override fun onCleared() {
        disconnect()
        ioScope.cancel()
        super.onCleared()
    }
}
