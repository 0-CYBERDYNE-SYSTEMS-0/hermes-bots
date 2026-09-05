package ai.hermes.bots.ui.groups

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.GroupLogEntry
import ai.hermes.bots.data.GroupRoom
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupChatUiState(
    val loading: Boolean = true,
    val room: GroupRoom? = null,
    val log: List<GroupLogEntry> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class GroupChatViewModel(app: Application, private val connectionId: String, private val roomId: String) :
    AndroidViewModel(app) {

    private val graph = (app as HermesBotsApp).graph

    private val _ui = MutableStateFlow(GroupChatUiState())
    val ui: StateFlow<GroupChatUiState> = _ui

    private var pollJob: Job? = null

    init {
        refresh()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(4_000)
                runCatching { loadRoom() }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                loadRoom()
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "load failed") }
            }
        }
    }

    private suspend fun loadRoom() {
        val (room, log) = graph.groups.roomState(connectionId, roomId)
        _ui.update { it.copy(loading = false, room = room, log = log, error = null) }
    }

    fun send(text: String) {
        if (_ui.value.busy || text.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                graph.groups.sendUserMessage(connectionId, roomId, text.trim())
                _ui.update { it.copy(busy = false) }
                delay(700)
                loadRoom()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "send failed") }
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            try {
                val n = graph.groups.stop(connectionId, roomId)
                _ui.update { it.copy(message = "Stopped ($n queued item(s) cancelled)") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "stop failed") }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
