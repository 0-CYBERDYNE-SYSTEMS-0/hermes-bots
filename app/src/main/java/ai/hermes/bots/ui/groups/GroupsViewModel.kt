package ai.hermes.bots.ui.groups

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.GroupCaps
import ai.hermes.bots.data.GroupRepository
import ai.hermes.bots.data.GroupRoom
import ai.hermes.bots.data.RosterEntry
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupsUiState(
    val loading: Boolean = true,
    val caps: GroupCaps? = null,
    val connectionId: String = "",
    val connectionLabel: String = "",
    val rooms: List<GroupRoom> = emptyList(),
    val roster: List<RosterEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class GroupsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as HermesBotsApp).graph

    private val _ui = MutableStateFlow(GroupsUiState())
    val ui: StateFlow<GroupsUiState> = _ui

    /** B4: bot names present on more than one connection across the union roster. */
    val collisionNames: StateFlow<Set<String>> = graph.roster.roster
        .map { rows -> ai.hermes.bots.data.BotNameCollisions.compute(rows.map { it.bot }) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        viewModelScope.launch {
            try {
                val conns = graph.connections.connections.first()
                val conn = conns.firstOrNull { it.primary } ?: conns.firstOrNull()
                    ?: throw IllegalStateException("no gateway connections")
                val caps = graph.groups.capabilities(conn.id)
                graph.groups.refreshRooms(conn.id)
                _ui.update {
                    it.copy(
                        loading = false,
                        caps = caps,
                        connectionId = conn.id,
                        connectionLabel = conn.label,
                        rooms = graph.groups.rooms.value[conn.id].orEmpty(),
                        roster = graph.roster.roster.value.filter { e -> e.bot.connectionId == conn.id && !e.bot.hidden },
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "load failed") }
            }
        }
    }

    fun refresh() {
        val connId = _ui.value.connectionId
        if (connId.isEmpty()) return
        viewModelScope.launch {
            try {
                graph.groups.refreshRooms(connId)
                _ui.update { it.copy(rooms = graph.groups.rooms.value[connId].orEmpty(), error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "refresh failed") }
            }
        }
    }

    fun create(name: String, members: List<RosterEntry>) {
        if (_ui.value.busy || members.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                graph.groups.createRoom(
                    _ui.value.connectionId,
                    name,
                    members.map { GroupRepository.RoomMember(it.bot.name, it.bot.displayName ?: it.bot.name) },
                )
                _ui.update { it.copy(busy = false, message = "Group created") }
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "create failed") }
            }
        }
    }

    fun disband(roomId: String) {
        viewModelScope.launch {
            try {
                graph.groups.disband(_ui.value.connectionId, roomId)
                _ui.update { it.copy(message = "Group disbanded") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "disband failed") }
            }
        }
    }
}
