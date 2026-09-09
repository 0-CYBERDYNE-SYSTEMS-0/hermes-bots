package ai.hermes.bots.ui.settings

import ai.hermes.bots.data.GatewayDraft
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.GatewayProbe
import ai.hermes.bots.ui.theme.brandPalette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Pre-filled "Add/Edit gateway" dialog opened by a provisioning deep link or QR (B1a).
 * Mirrors the Gateways editor fields; Save goes through ConnectionRepository.upsert, which
 * normalizes the URL (B8). When the link's address matches an existing gateway, the dialog
 * edits that record instead of creating a duplicate.
 */
@Composable
fun FleetProvisionDialog(
    draft: GatewayDraft,
    onProbe: suspend (String, GatewayAuth) -> GatewayProbe,
    onDismiss: () -> Unit,
    onSave: (GatewayDraft) -> Unit,
) {
    var label by remember { mutableStateOf(draft.label) }
    var baseUrl by remember { mutableStateOf(draft.baseUrl) }
    var useBasic by remember { mutableStateOf(draft.useBasic) }
    var username by remember { mutableStateOf(draft.username) }
    var password by remember { mutableStateOf(draft.password) }
    var token by remember { mutableStateOf(draft.token) }
    var probeResult by remember { mutableStateOf<GatewayProbe?>(null) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.existingId == null) "Add gateway" else "Edit gateway") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (draft.existingId == null) {
                    Text(
                        "Check the details, then save to connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "This gateway already exists — review and update it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; probeResult = null },
                    label = { Text("Address") },
                    supportingText = { Text("e.g. 100.x.y.z:9300 or https://name.ts.net") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !useBasic, onClick = { useBasic = false }, label = { Text("Token") })
                    FilterChip(selected = useBasic, onClick = { useBasic = true }, label = { Text("User + pass") })
                }
                if (useBasic) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; probeResult = null },
                        label = { Text("Session token") },
                        supportingText = { Text("The dashboard token your gateway was started with") },
                        singleLine = true,
                    )
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (probe.reachable) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = brandPalette().success,
                                )
                                Text(
                                    "Reachable · Hermes v${probe.version ?: "?"}" +
                                        if (probe.authRequired == true) " · sign-in required" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = brandPalette().success,
                                )
                            } else {
                                Text(
                                    "Couldn't reach it — check the address.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        draft.copy(
                            label = label,
                            baseUrl = baseUrl,
                            useBasic = useBasic,
                            username = username,
                            password = password,
                            token = token,
                        ),
                    )
                },
                enabled = baseUrl.isNotBlank() && (useBasic || token.isNotBlank()),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
