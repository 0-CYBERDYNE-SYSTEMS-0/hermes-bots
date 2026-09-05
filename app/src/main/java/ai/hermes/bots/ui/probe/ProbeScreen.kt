package ai.hermes.bots.ui.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeScreen(vm: ProbeViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Hermes Bots — probe") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = ui.baseUrl,
                onValueChange = vm::updateBaseUrl,
                label = { Text("Gateway base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = ui.token,
                onValueChange = vm::updateToken,
                label = { Text("Session token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::testProbe) { Text("Probe") }
                Button(onClick = vm::connect) { Text("Connect") }
                OutlinedButton(onClick = vm::fetchRoster) { Text("profiles.list") }
                OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
            }
            ui.probe?.let { probe ->
                Text(
                    "reachable=${probe.reachable} auth_required=${probe.authRequired} " +
                        "http=${probe.httpCode} version=${probe.version ?: "-"} " +
                        (probe.error?.let { "error=$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "socket: ${ui.socketState::class.simpleName} ${ui.socketState}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (ui.bots.isNotEmpty()) {
                Text("Roster:", style = MaterialTheme.typography.titleSmall)
                LazyColumn(modifier = Modifier.weight(0.35f)) {
                    items(ui.bots) { bot -> Text(bot, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text("Log:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.weight(0.65f)) {
                items(ui.log) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
