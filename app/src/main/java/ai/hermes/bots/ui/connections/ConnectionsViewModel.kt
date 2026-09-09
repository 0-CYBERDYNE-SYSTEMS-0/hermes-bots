package ai.hermes.bots.ui.connections

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.RelayStatus
import ai.hermes.bots.protocol.Auth
import ai.hermes.bots.protocol.FleetProbeResult
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.GatewayProbe
import ai.hermes.bots.protocol.SocketState
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ConnectionsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as HermesBotsApp).graph

    val connections: StateFlow<List<ConnectionRecord>> = graph.connections.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val socketStates: StateFlow<Map<String, SocketState>> = graph.gateways.socketStates

    /** Per-connection relay capability + last drain (B5), for the gateway card badge. */
    val relayStatus: StateFlow<Map<String, RelayStatus>> = graph.gateways.relayStatus

    /** Last full verification result per normalized base URL (B6), for the card chip. */
    private val _verifyResults = MutableStateFlow<Map<String, FleetProbeResult>>(emptyMap())
    val verifyResults: StateFlow<Map<String, FleetProbeResult>> = _verifyResults

    fun upsert(record: ConnectionRecord) {
        viewModelScope.launch { graph.connections.upsert(record) }
    }

    fun delete(id: String) {
        viewModelScope.launch { graph.connections.delete(id) }
    }

    fun setPrimary(id: String) {
        viewModelScope.launch { graph.connections.setPrimary(id) }
    }

    suspend fun probe(baseUrl: String, auth: GatewayAuth): GatewayProbe {
        val normalized = Auth.normalizeBaseUrl(baseUrl)
        return graph.gateways.probe(ConnectionRecord("probe", "probe", normalized, auth))
    }

    /** Full verification probe (B6): health + sign-in + chat channel + groups/relay bits. */
    suspend fun verify(baseUrl: String, auth: GatewayAuth): FleetProbeResult {
        val normalized = Auth.normalizeBaseUrl(baseUrl)
        val result = graph.gateways.verifyFleet(ConnectionRecord("probe", "probe", normalized, auth))
        _verifyResults.update { it + (normalized to result) }
        return result
    }

    fun normalize(baseUrl: String): String = Auth.normalizeBaseUrl(baseUrl)
}
