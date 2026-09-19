package ai.hermes.bots.ui.groups

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.GroupLogEntry
import ai.hermes.bots.data.GroupPendingAction
import ai.hermes.bots.data.GroupRepository
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
    /** Actions waiting on the user: approvals to resolve, stuck rounds to retry. */
    val pending: List<GroupPendingAction> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** One-shot: the room was disbanded and the screen should navigate back. */
    val disbanded: Boolean = false,
)

class GroupChatViewModel(app: Application, private val connectionId: String, private val roomId: String) :
    AndroidViewModel(app) {

    private val graph = (app as HermesBotsApp).graph

    private val _ui = MutableStateFlow(GroupChatUiState())
    val ui: StateFlow<GroupChatUiState> = _ui

    private var pollJob: Job? = null

    /** Last room-log seq seen — sent as since_seq so polls fetch deltas, not window 0. */
    private var lastSeq = 0L

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
        val state = graph.groups.roomState(connectionId, roomId, lastSeq)
        _ui.update { cur ->
            val (merged, seq) = GroupRepository.mergeLog(cur.log, state.log, lastSeq)
            lastSeq = seq
            cur.copy(loading = false, room = state.room, log = merged, pending = state.pending, error = null)
        }
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

    fun disband() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            try {
                graph.groups.disband(connectionId, roomId)
                _ui.update { it.copy(disbanded = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "disband failed") }
            }
        }
    }

    /** Resolve a member's approval request — choice "once" or "deny" (PROTOCOL.md §5.6). */
    fun resolve(action: GroupPendingAction, choice: String) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                graph.groups.resolvePending(connectionId, roomId, action, choice)
                _ui.update {
                    it.copy(busy = false, message = if (choice == "deny") "Denied" else "Approved")
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "approve failed") }
            }
        }
    }

    fun retry(action: GroupPendingAction) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                graph.groups.retryPending(connectionId, roomId, action)
                _ui.update { it.copy(busy = false, message = "Retrying that round…") }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "retry failed") }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
