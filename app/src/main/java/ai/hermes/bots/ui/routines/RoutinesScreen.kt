package ai.hermes.bots.ui.routines

import ai.hermes.bots.data.CronJob
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
import androidx.compose.material3.Switch
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
fun RoutinesScreen(
    connectionId: String,
    botName: String,
    onBack: () -> Unit,
    vm: RoutinesViewModel = viewModel(
        key = "routines:$connectionId:$botName",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ai.hermes.bots.HermesBotsApp
                RoutinesViewModel(app, connectionId, botName)
            }
        },
    ),
) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CronJob?>(null) }

    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(ui.error) { ui.error?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routines — $botName") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) { Icon(Icons.Filled.Add, contentDescription = "Add routine") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (ui.loading) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
            if (!ui.loading && ui.jobs.isEmpty()) {
                Text(
                    "No routines yet. Add one with + — it runs on this bot's schedule.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.jobs, key = { it.id }) { job ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(job.name, style = MaterialTheme.typography.titleSmall)
                                    Text(job.scheduleText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                }
                                Switch(checked = job.enabled, onCheckedChange = { vm.setEnabled(job.id, it) })
                            }
                            if (job.prompt.isNotBlank()) {
                                Text(
                                    job.prompt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { editing = job }) { Text("Edit") }
                                TextButton(onClick = { vm.delete(job.id) }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        RoutineDialog(
            title = "New routine",
            initialName = "",
            initialSchedule = "0 9 * * *",
            initialPrompt = "",
            onDismiss = { adding = false },
            onSave = { name, schedule, prompt ->
                vm.create(schedule, prompt, name)
                adding = false
            },
        )
    }
    editing?.let { job ->
        RoutineDialog(
            title = "Edit routine",
            initialName = job.name,
            initialSchedule = job.scheduleText,
            initialPrompt = job.prompt,
            onDismiss = { editing = null },
            onSave = { _, schedule, prompt ->
                vm.updateJob(job.id, schedule, prompt)
                editing = null
            },
        )
    }
}

@Composable
private fun RoutineDialog(
    title: String,
    initialName: String,
    initialSchedule: String,
    initialPrompt: String,
    onDismiss: () -> Unit,
    onSave: (name: String, schedule: String, prompt: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var schedule by remember { mutableStateOf(initialSchedule) }
    var prompt by remember { mutableStateOf(initialPrompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(value = schedule, onValueChange = { schedule = it }, label = { Text("Schedule (cron expr or \"every 30m\")") }, singleLine = true)
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Prompt") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), schedule.trim(), prompt.trim()) },
                enabled = schedule.isNotBlank() && prompt.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
