package ai.hermes.bots.ui.roster

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.BotNameCollisions
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.RosterEntry
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The user's main assistant bot on the primary gateway (profile name, not a display name). */
const val DEFAULT_ASSISTANT_NAME = "default"

class RosterViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as HermesBotsApp).graph

    val roster: StateFlow<List<RosterEntry>> = graph.roster.roster
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val avatars: StateFlow<Map<String, AvatarImage>> = graph.roster.avatars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val connections: StateFlow<List<ConnectionRecord>> = graph.connections.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** B4: bot names present on more than one connection across the union roster. */
    val collisionNames: StateFlow<Set<String>> = graph.roster.roster
        .map { rows -> BotNameCollisions.compute(rows.map { it.bot }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Easy-access: the primary gateway's `default` assistant, pinned at the very top. */
    val assistant: StateFlow<RosterEntry?> = combine(graph.roster.roster, graph.connections.connections) { rows, conns ->
        val primary = conns.firstOrNull { it.primary } ?: return@combine null
        rows.firstOrNull { it.bot.connectionId == primary.id && it.bot.name == DEFAULT_ASSISTANT_NAME }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Bell badge: any notification history exists (display-only, audit A1). */
    val hasNotifications: StateFlow<Boolean> = graph.settings.notificationHistory
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
                // CAS conflicts resolve on the next 5 s poll + manual retry
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
            }
        }
    }
}
