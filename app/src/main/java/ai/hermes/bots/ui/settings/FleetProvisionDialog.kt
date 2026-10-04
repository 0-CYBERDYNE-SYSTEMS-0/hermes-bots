package ai.hermes.bots.ui.settings

import ai.hermes.bots.data.GatewayDraft
import ai.hermes.bots.data.FleetProvisioning
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.FleetProbeResult
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Pre-filled "Add/Edit gateway" dialog opened by a provisioning deep link or QR.
 * Mirrors the Gateways editor fields; Save goes through ConnectionRepository.upsert, which
 * normalizes the URL. When the link's address matches an existing gateway, the dialog
 * edits that record instead of creating a duplicate.
 */
@Composable
fun FleetProvisionDialog(
    draft: GatewayDraft,
    onVerify: suspend (String, GatewayAuth) -> FleetProbeResult,
    onDismiss: () -> Unit,
    onSave: (GatewayDraft) -> Unit,
) {
    var label by remember { mutableStateOf(draft.label) }
    var baseUrl by remember { mutableStateOf(draft.baseUrl) }
    var useBasic by remember { mutableStateOf(draft.useBasic) }
    var username by remember { mutableStateOf(draft.username) }
    var password by remember { mutableStateOf(draft.password) }
    var token by remember { mutableStateOf(draft.token) }
    var verifyResult by remember { mutableStateOf<FleetProbeResult?>(null) }
    var verifying by remember { mutableStateOf(false) }
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
                        "Check the address and credentials now, or save and verify later from Gateways.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "This gateway already exists. Verify your changes here, or save and verify later from Gateways.",
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
                    onValueChange = { baseUrl = it; verifyResult = null },
                    label = { Text("Address") },
                    supportingText = { Text("e.g. 100.x.y.z:9300 or https://name.ts.net") },
                    enabled = !verifying,
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !useBasic,
                        onClick = { useBasic = false; verifyResult = null },
                        enabled = !verifying,
                        label = { Text("Token") },
                    )
                    FilterChip(
                        selected = useBasic,
                        onClick = { useBasic = true; verifyResult = null },
                        enabled = !verifying,
                        label = { Text("User + pass") },
                    )
                }
                if (useBasic) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; verifyResult = null },
                        label = { Text("Username") },
                        enabled = !verifying,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; verifyResult = null },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !verifying,
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; verifyResult = null },
                        label = { Text("Session token") },
                        supportingText = { Text("The dashboard token your gateway was started with") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !verifying,
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
                                try {
                                    verifyResult = onVerify(FleetProvisioning.normalizeBaseUrl(baseUrl), auth)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    verifyResult = FleetProbeResult(
                                        failure = "Couldn't verify this gateway. Check the address and credentials, then retry.",
                                    )
                                } finally {
                                    verifying = false
                                }
                            }
                        },
                        enabled = !verifying && baseUrl.isNotBlank() &&
                            if (useBasic) username.isNotBlank() && password.isNotBlank() else token.isNotBlank(),
                    ) { Text(if (verifying) "Verifying…" else "Verify") }
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
                                    "Verified · Hermes v${result.serverVersion ?: "?"}",
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
                enabled = !verifying && baseUrl.isNotBlank() &&
                    if (useBasic) username.isNotBlank() && password.isNotBlank() else token.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
