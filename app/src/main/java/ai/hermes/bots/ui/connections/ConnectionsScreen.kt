package ai.hermes.bots.ui.connections

import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.RelayStatus
import ai.hermes.bots.data.RelaySupport
import ai.hermes.bots.protocol.Auth
import ai.hermes.bots.protocol.FleetProbeResult
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.SocketState
import ai.hermes.bots.ui.theme.Dimens
import ai.hermes.bots.ui.theme.brandPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(onBack: () -> Unit, vm: ConnectionsViewModel = viewModel()) {
    val connections by vm.connections.collectAsState()
    val socketStates by vm.socketStates.collectAsState()
    val relayStatus by vm.relayStatus.collectAsState()
    val verifyResults by vm.verifyResults.collectAsState()
    var editing by remember { mutableStateOf<ConnectionRecord?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.GutterScreen),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(connections, key = { it.id }) { conn ->
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (pressed) 0.98f else 1f,
                    label = "press-scale",
                )
                Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
                    ConnectionCard(
                        record = conn,
                        state = socketStates[conn.id] ?: SocketState.Idle,
                        relay = relayStatus[conn.id],
                        verified = verifyResults[conn.baseUrl]
                            ?: verifyResults[Auth.normalizeBaseUrl(conn.baseUrl)],
                        onEdit = { editing = conn },
                        onSetPrimary = { vm.setPrimary(conn.id) },
                    )
                }
            }
        }
    }

    if (adding || editing != null) {
        ConnectionEditDialog(
            initial = editing,
            canSetPrimary = editing?.primary == false,
            onSetPrimary = {
                editing?.let { vm.setPrimary(it.id) }
                adding = false
                editing = null
            },
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
            onVerify = { baseUrl, auth -> vm.verify(baseUrl, auth) },
        )
    }
}

@Composable
private fun ConnectionCard(
    record: ConnectionRecord,
    state: SocketState,
    relay: RelayStatus?,
    verified: FleetProbeResult?,
    onEdit: () -> Unit,
    onSetPrimary: () -> Unit,
) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
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
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.label, style = MaterialTheme.typography.titleMedium)
                    if (record.primary) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Primary",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    when (state) {
                        is SocketState.Ready -> "Connected"
                        is SocketState.Connecting -> "Connecting…"
                        is SocketState.Disconnected -> "Offline — couldn't reach gateway"
                        SocketState.Idle -> "Offline"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        is SocketState.Ready -> brandPalette().success
                        is SocketState.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                // Subtle trust line (B5/B6): full verification after Test, otherwise the
                // relay engine's live view of this gateway's bot-to-bot support.
                val detail = cardDetail(state, relay, verified)
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun cardDetail(state: SocketState, relay: RelayStatus?, verified: FleetProbeResult?): String? {
    if (verified?.verified == true && state is SocketState.Ready) {
        return buildString {
            append("Verified ✓")
            verified.serverVersion?.let { append(" · v").append(it) }
            append(" · groups ").append(capBit(verified.groupsSupported))
            append(" · relay ").append(capBit(verified.relaySupported))
        }
    }
    val status = relay?.support ?: return null
    return when (status) {
        RelaySupport.Supported -> buildString {
            append("Relay on")
            relay.lastDrainMs?.let { append(" · checked ").append(agoShort(it)) }
        }
        RelaySupport.Unsupported -> "Relay off"
        RelaySupport.Unknown -> "Relay ?"
    }
}

private fun capBit(value: Boolean?): String = when (value) {
    true -> "✓"
    false -> "✗"
    null -> "?"
}

private fun agoShort(ms: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - ms).coerceAtLeast(0)
    return when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
        else -> "${delta / 86_400_000L}d ago"
    }
}

@Composable
private fun ConnectionEditDialog(
    initial: ConnectionRecord?,
    canSetPrimary: Boolean,
    onSetPrimary: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (ConnectionRecord) -> Unit,
    onDelete: (String) -> Unit,
    onVerify: suspend (String, GatewayAuth) -> FleetProbeResult,
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
    var verifyResult by remember { mutableStateOf<FleetProbeResult?>(null) }
    var verifying by remember { mutableStateOf(false) }
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
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; verifyResult = null },
                    label = { Text("Address") },
                    supportingText = { Text("e.g. 100.x.y.z:9300 or https://name.ts.net") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !useBasic, onClick = { useBasic = false; verifyResult = null }, label = { Text("Token") })
                    FilterChip(selected = useBasic, onClick = { useBasic = true; verifyResult = null }, label = { Text("User + pass") })
                }
                if (useBasic) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; verifyResult = null },
                        label = { Text("Username") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; verifyResult = null },
                        label = { Text("Password") },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; verifyResult = null },
                        label = { Text("Session token") },
                        supportingText = { Text("The dashboard token your gateway was started with") },
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            verifying = true
                            scope.launch {
                                val auth = if (useBasic) GatewayAuth.BasicAuth(username, password)
                                else GatewayAuth.TokenAuth(token)
                                verifyResult = onVerify(baseUrl, auth)
                                verifying = false
                            }
                        },
                        enabled = !verifying && baseUrl.isNotBlank(),
                    ) { Text(if (verifying) "Verifying…" else "Test") }
                    verifyResult?.let { result ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (result.verified) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = brandPalette().success,
                                )
                                Text(
                                    verificationLine(result),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = brandPalette().success,
                                )
                            } else {
                                Text(
                                    result.failure ?: "Couldn't verify this gateway.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                if (canSetPrimary) {
                    TextButton(onClick = onSetPrimary) { Text("Set as primary") }
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

/** "Verified ✓ · v0.21.0 · groups ✓ · relay ✓" (FLEET-CONNECT-SPEC B6). */
private fun verificationLine(result: FleetProbeResult): String = buildString {
    append("Verified ✓")
    result.serverVersion?.let { append(" · v").append(it) }
    append(" · groups ").append(capBit(result.groupsSupported))
    append(" · relay ").append(capBit(result.relaySupported))
}
