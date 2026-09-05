package ai.hermes.bots.ui.connections

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.protocol.Auth
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.GatewayProbe
import ai.hermes.bots.protocol.SocketState
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConnectionsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as HermesBotsApp).graph

    val connections: StateFlow<List<ConnectionRecord>> = graph.connections.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val socketStates: StateFlow<Map<String, SocketState>> = graph.gateways.socketStates

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

    fun normalize(baseUrl: String): String = Auth.normalizeBaseUrl(baseUrl)
}
