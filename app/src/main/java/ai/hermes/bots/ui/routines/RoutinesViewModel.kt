package ai.hermes.bots.ui.routines

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.CronJob
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

data class RoutinesUiState(
    val loading: Boolean = true,
    val jobs: List<CronJob> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class RoutinesViewModel(app: Application, private val connectionId: String, private val botName: String) :
    AndroidViewModel(app) {

    private val graph = (app as HermesBotsApp).graph

    private val _ui = MutableStateFlow(RoutinesUiState())
    val ui: StateFlow<RoutinesUiState> = _ui

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = _ui.value.jobs.isEmpty()) }
            try {
                val jobs = graph.cron.listProfile(connectionId, botName)
                _ui.update { it.copy(loading = false, jobs = jobs, error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "load failed") }
            }
        }
    }

    fun create(schedule: String, prompt: String, name: String) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                graph.cron.create(connectionId, botName, prompt, schedule, name)
                _ui.update { it.copy(busy = false, message = "Routine added") }
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "create failed") }
            }
        }
    }

    fun updateJob(jobId: String, schedule: String, prompt: String) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                graph.cron.update(connectionId, jobId, kotlinx.serialization.json.buildJsonObject {
                    put("schedule", schedule)
                    put("prompt", prompt)
                })
                _ui.update { it.copy(busy = false, message = "Routine updated") }
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "update failed") }
            }
        }
    }

    fun setEnabled(jobId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                graph.cron.setEnabled(connectionId, jobId, enabled)
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "toggle failed") }
            }
        }
    }

    fun delete(jobId: String) {
        viewModelScope.launch {
            try {
                graph.cron.delete(connectionId, jobId)
                _ui.update { it.copy(message = "Routine deleted") }
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "delete failed") }
            }
        }
    }
}
