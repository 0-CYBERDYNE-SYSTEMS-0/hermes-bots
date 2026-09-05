package ai.hermes.bots.ui.roster

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.RosterEntry
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RosterViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as HermesBotsApp).graph

    val roster: StateFlow<List<RosterEntry>> = graph.roster.roster
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val avatars: StateFlow<Map<String, AvatarImage>> = graph.roster.avatars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val connections: StateFlow<List<ConnectionRecord>> = graph.connections.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
