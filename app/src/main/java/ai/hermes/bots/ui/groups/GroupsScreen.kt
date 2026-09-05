package ai.hermes.bots.ui.groups

import ai.hermes.bots.data.RosterEntry
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onBack: () -> Unit,
    onOpenRoom: (connectionId: String, roomId: String, roomName: String) -> Unit,
    vm: GroupsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ai.hermes.bots.HermesBotsApp
                GroupsViewModel(app)
            }
        },
    ),
) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(ui.error) { ui.error?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group chats") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (ui.caps?.supported == true) {
                FloatingActionButton(
                    onClick = { creating = true },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) { Icon(Icons.Filled.Add, contentDescription = "New group") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (ui.loading) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            ui.caps?.let { caps ->
                if (!caps.supported) {
                    Text(
                        "This gateway doesn't support hosted group chats (groups.* RPCs missing).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    return@Column
                }
                Text(
                    "${ui.connectionLabel} · protocol ${caps.protocolVersion}" + if (caps.driverReady) "" else " · driver stopped",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            val active = ui.rooms.filter { !it.disbanded }
            if (active.isEmpty()) {
                Text(
                    "No group chats yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(active, key = { it.roomId }) { room ->
                    Card(
                        onClick = { onOpenRoom(room.connectionId, room.roomId, room.name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(room.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                room.members.joinToString(", ").ifBlank { "no members" },
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

    if (creating) {
        CreateGroupDialog(
            roster = ui.roster,
            busy = ui.busy,
            onDismiss = { creating = false },
            onCreate = { name, members ->
                vm.create(name, members)
                creating = false
            },
        )
    }
}

@Composable
private fun CreateGroupDialog(
    roster: List<RosterEntry>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, members: List<RosterEntry>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Group name") }, singleLine = true)
                Text("Members", style = MaterialTheme.typography.labelMedium)
                Text("Pick 2–6 members (gateway requirement)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (roster.isEmpty()) Text("No bots on this gateway yet.", style = MaterialTheme.typography.bodySmall)
                roster.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = entry.bot.name in selected.value,
                            onCheckedChange = { checked ->
                                selected.value = if (checked) selected.value + entry.bot.name else selected.value - entry.bot.name
                            },
                        )
                        Text(entry.bot.displayName ?: entry.bot.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name.trim(), roster.filter { it.bot.name in selected.value })
                },
                enabled = !busy && name.isNotBlank() && selected.value.size >= 2,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
