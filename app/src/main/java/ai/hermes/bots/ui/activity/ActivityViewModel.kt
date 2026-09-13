package ai.hermes.bots.ui.activity

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.PendingApproval
import ai.hermes.bots.ui.util.Humanize
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the Activity screen (UI-SPEC.md §4.6); read-only views over the graph. */
class ActivityViewModel(app: Application) : AndroidViewModel(app) {
  private val graph = (app as HermesBotsApp).graph

  val pending = graph.inbox.pending
  val roster = graph.roster.roster
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
  val avatars = graph.roster.avatars
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
  val jobs = graph.cron.jobs
  val history = graph.settings.notificationHistory

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error

  /** Quick action from a needs-you row; failures surface as a snackbar, row stays. */
  fun respond(pendingApproval: PendingApproval, choice: String) {
    viewModelScope.launch {
      try {
        graph.inbox.respond(pendingApproval, choice)
      } catch (e: Exception) {
        _error.value = Humanize.friendlyError(e.message, pendingApproval.botName ?: "this bot")
          ?: "Couldn't respond — try again."
      }
    }
  }

  fun consumeError() {
    _error.value = null
  }
}
