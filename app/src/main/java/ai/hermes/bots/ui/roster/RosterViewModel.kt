package ai.hermes.bots.ui.roster

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.MergedBot
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.RosterRepository
import ai.hermes.bots.data.rowsPerGateway
import ai.hermes.bots.protocol.SocketState
import ai.hermes.bots.ui.util.Humanize
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The user's main assistant bot on the primary gateway (profile name, not a display name). */
const val DEFAULT_ASSISTANT_NAME = "default"

/** The roster as rendered: one row per bot per gateway + the pinned assistant row (if any). */
data class MergedRoster(val rows: List<MergedBot>, val pinnedAssistant: MergedBot?)

class RosterViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as HermesBotsApp).graph

    /** Every bot on every connected gateway gets its own row — no same-name collapsing. */
    val merged: StateFlow<MergedRoster> = combine(
        graph.roster.roster,
        graph.gateways.socketStates,
        graph.connections.connections,
    ) { rows, states, conns ->
        val primaryId = conns.firstOrNull { it.primary }?.id
        val list = rowsPerGateway(rows, conns.associate { it.id to it.label })
        val pinned = list.firstOrNull {
            it.name.equals(DEFAULT_ASSISTANT_NAME, ignoreCase = true) && it.primary.bot.connectionId == primaryId
        }
        MergedRoster(list, pinned)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MergedRoster(emptyList(), null))

    val avatars: StateFlow<Map<String, AvatarImage>> = graph.roster.avatars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val connections: StateFlow<List<ConnectionRecord>> = graph.connections.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** SV-14: bell badge only while a history entry is newer than the seen watermark. */
    val hasNotifications: StateFlow<Boolean> = combine(
        graph.settings.notificationHistory,
        graph.settings.historySeenAt,
    ) { history, seenAt ->
        (history.firstOrNull()?.atMs ?: 0L) > seenAt
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** SV-17: per-connection socket state, for the offline roster empty state. */
    val socketStates: StateFlow<Map<String, SocketState>> = graph.gateways.socketStates

    /** SV-11: one-shot humanized failure for roster actions (hide / move to section). */
    private val _transientError = MutableStateFlow<String?>(null)
    val transientError: StateFlow<String?> = _transientError

    fun consumeTransientError() {
        _transientError.value = null
    }

    /** Pull-to-refresh state (immediate poll of every Ready gateway). */
    val refreshing: StateFlow<Boolean> = graph.roster.refreshing

    fun refresh() {
        viewModelScope.launch { graph.roster.refreshNow() }
    }

    fun markRead(connectionId: String, botName: String) {
        graph.roster.markRead(connectionId, botName)
    }

    private val admin by lazy { ai.hermes.bots.data.BotAdminRepository(graph.gateways) }

    fun setHidden(connectionId: String, botName: String, hidden: Boolean) {
        viewModelScope.launch {
            try {
                val row = graph.roster.roster.value.firstOrNull { it.bot.connectionId == connectionId && it.bot.name == botName }
                admin.configure(
                    connectionId = connectionId,
                    name = botName,
                    uiMeta = ai.hermes.bots.data.BotAdmin.mergeUiMeta(null, ai.hermes.bots.data.BotAdmin.UiMetaPatch(hidden = hidden)),
                    expectedRevisions = row?.bot?.uiMetaRevisions,
                )
            } catch (e: Exception) {
                // CAS conflicts resolve on the next 5 s poll; everything else surfaces (SV-11).
                _transientError.value = Humanize.friendlyError(e.message, botName)
                    ?: "Couldn't reach the gateway — try again."
            }
        }
    }

    fun setSection(connectionId: String, botName: String, sectionId: String) {
        viewModelScope.launch {
            try {
                val row = graph.roster.roster.value.firstOrNull { it.bot.connectionId == connectionId && it.bot.name == botName }
                admin.configure(
                    connectionId = connectionId,
                    name = botName,
                    uiMeta = ai.hermes.bots.data.BotAdmin.mergeUiMeta(null, ai.hermes.bots.data.BotAdmin.UiMetaPatch(sectionId = sectionId.ifBlank { null })),
                    expectedRevisions = row?.bot?.uiMetaRevisions,
                )
            } catch (e: Exception) {
                _transientError.value = Humanize.friendlyError(e.message, botName)
                    ?: "Couldn't reach the gateway — try again."
            }
        }
    }
}
