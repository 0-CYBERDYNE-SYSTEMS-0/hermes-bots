package ai.hermes.bots.ui.connections

import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.GatewayProbe
import ai.hermes.bots.protocol.SocketState
import ai.hermes.bots.ui.theme.brandPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(onBack: () -> Unit, vm: ConnectionsViewModel = viewModel()) {
    val connections by vm.connections.collectAsState()
    val socketStates by vm.socketStates.collectAsState()
    var editing by remember { mutableStateOf<ConnectionRecord?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gateways") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add gateway")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(connections, key = { it.id }) { conn ->
                ConnectionCard(
                    record = conn,
                    state = socketStates[conn.id] ?: SocketState.Idle,
                    onEdit = { editing = conn },
                    onSetPrimary = { vm.setPrimary(conn.id) },
                )
            }
        }
    }

    if (adding || editing != null) {
        ConnectionEditDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { record ->
                vm.upsert(record)
                adding = false
                editing = null
            },
            onDelete = { id ->
                vm.delete(id)
                adding = false
                editing = null
            },
            onProbe = { baseUrl, auth -> vm.probe(baseUrl, auth) },
        )
    }
}

@Composable
private fun ConnectionCard(
    record: ConnectionRecord,
    state: SocketState,
    onEdit: () -> Unit,
    onSetPrimary: () -> Unit,
) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when (state) {
                                is SocketState.Ready -> brandPalette().success
                                is SocketState.Connecting -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            },
                        ),
                )
                Text(record.label, style = MaterialTheme.typography.titleMedium)
                if (record.primary) {
                    Spacer(Modifier.padding(start = 4.dp))
                    Icon(Icons.Filled.Star, contentDescription = "Primary", tint = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.weight(1f))
                StateChip(state)
            }
            Text(record.baseUrl, style = MaterialTheme.typography.bodySmall)
            val authLabel = when (val auth = record.auth) {
                is GatewayAuth.TokenAuth -> "token auth"
                is GatewayAuth.BasicAuth -> "basic auth (${auth.username})"
            }
            Text(authLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when (state) {
                    is SocketState.Ready -> "connected"
                    is SocketState.Connecting -> "connecting…"
                    is SocketState.Disconnected -> "disconnected: ${state.detail}"
                    SocketState.Idle -> "idle"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    is SocketState.Ready -> MaterialTheme.colorScheme.primary
                    is SocketState.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun StateChip(state: SocketState) {
    val (label, selected) = when (state) {
        is SocketState.Ready -> "READY" to true
        is SocketState.Connecting -> "…" to false
        else -> "OFF" to false
    }
    AssistChip(onClick = ::onSetPrimaryNoop, label = { Text(label) })
}

private fun onSetPrimaryNoop() = Unit

@Composable
private fun ConnectionEditDialog(
    initial: ConnectionRecord?,
    onDismiss: () -> Unit,
    onSave: (ConnectionRecord) -> Unit,
    onDelete: (String) -> Unit,
    onProbe: suspend (String, GatewayAuth) -> GatewayProbe,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "http://127.0.0.1:9119") }
    var useBasic by remember { mutableStateOf(initial?.auth is GatewayAuth.BasicAuth) }
    var token by remember {
        mutableStateOf((initial?.auth as? GatewayAuth.TokenAuth)?.token ?: "")
    }
    var username by remember {
        mutableStateOf((initial?.auth as? GatewayAuth.BasicAuth)?.username ?: "")
    }
    var password by remember {
        mutableStateOf((initial?.auth as? GatewayAuth.BasicAuth)?.password ?: "")
    }
    var probeResult by remember { mutableStateOf<GatewayProbe?>(null) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add gateway" else "Edit gateway") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it; probeResult = null }, label = { Text("Base URL (host[:port] or full URL)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !useBasic, onClick = { useBasic = false }, label = { Text("Token") })
                    FilterChip(selected = useBasic, onClick = { useBasic = true }, label = { Text("User + pass") })
                }
                if (useBasic) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true)
                } else {
                    OutlinedTextField(value = token, onValueChange = { token = it; probeResult = null }, label = { Text("Session token (HERMES_DASHBOARD_SESSION_TOKEN)") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            probing = true
                            scope.launch {
                                val auth = if (useBasic) GatewayAuth.BasicAuth(username, password)
                                else GatewayAuth.TokenAuth(token)
                                probeResult = onProbe(baseUrl, auth)
                                probing = false
                            }
                        },
                        enabled = !probing && baseUrl.isNotBlank(),
                    ) { Text(if (probing) "Testing…" else "Test") }
                    probeResult?.let { probe ->
                        Text(
                            if (probe.reachable) {
                                "reachable · auth_required=${probe.authRequired} · v${probe.version ?: "?"}"
                            } else {
                                "unreachable: ${probe.error ?: "unknown"}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (probe.reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial.id) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val auth = if (useBasic) GatewayAuth.BasicAuth(username.trim(), password)
                    else GatewayAuth.TokenAuth(token.trim())
                    onSave(
                        (initial ?: ConnectionRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            label = "",
                            baseUrl = "",
                            auth = auth,
                        )).copy(
                            label = label.trim().ifEmpty { "Gateway" },
                            baseUrl = baseUrl.trim(),
                            auth = auth,
                        ),
                    )
                },
                enabled = baseUrl.isNotBlank() && (useBasic || token.isNotBlank()),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
