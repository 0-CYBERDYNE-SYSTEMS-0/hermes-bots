package ai.hermes.bots.ui.chats

import ai.hermes.bots.ui.roster.RosterViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** Conversation entry point; each row retains its gateway identity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onOpenChat: (connectionId: String, botName: String) -> Unit,
    onOpenAnyChats: () -> Unit,
    onOpenGroupChats: () -> Unit,
    vm: RosterViewModel = viewModel(),
) {
    val roster by vm.merged.collectAsState()
    val connections by vm.connections.collectAsState()
    val labels = connections.associate { it.id to it.label }
    val recent = roster.rows
        .filterNot { it.hidden }
        .sortedByDescending { it.lastActiveMs ?: 0L }

    Scaffold(topBar = { TopAppBar(title = { Text("Chats") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(onClick = onOpenAnyChats, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.List, contentDescription = null)
                            Text("Any chats", style = MaterialTheme.typography.titleSmall)
                            Text("Talk across gateways", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Card(onClick = onOpenGroupChats, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.DateRange, contentDescription = null)
                            Text("Group chats", style = MaterialTheme.typography.titleSmall)
                            Text("Rooms on a gateway", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text(
                    "Recent bots",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            if (recent.isEmpty()) {
                item { Text("No bot conversations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(recent, key = { "${it.primary.bot.connectionId}:${it.name}" }) { bot ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onOpenChat(bot.primary.bot.connectionId, bot.name)
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(bot.displayName ?: bot.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(labels[bot.primary.bot.connectionId], bot.preview).joinToString(" · ")
                                    .ifBlank { "No recent preview" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
