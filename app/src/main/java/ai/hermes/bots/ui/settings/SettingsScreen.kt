package ai.hermes.bots.ui.settings

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.NotificationEntry
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.data.SettingsRepository
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as HermesBotsApp).graph
    private val settings = graph.settings

    val themeMode: StateFlow<String> = settings.themeMode
    val notificationsEnabled: StateFlow<Boolean> = settings.notificationsEnabled
    val notificationHistory: StateFlow<List<NotificationEntry>> = settings.notificationHistory

    // Roster + avatars for display-time notification resolution (V3): recorded labels can
    // carry the app-name fallback; resolve against known bots without re-writing history.
    val roster: StateFlow<List<RosterEntry>> = graph.roster.roster
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val avatars: StateFlow<Map<String, AvatarImage>> = graph.roster.avatars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setNotificationsEnabled(enabled) }
    }

    fun clearNotifications() {
        viewModelScope.launch { settings.clearNotifications() }
    }

    /** Fleet config export (B1c): every connection, secrets included per spec. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        ai.hermes.bots.data.FleetConfigCodec.export(graph.connections.connections.first())
    }

    /** Fleet config import (B1c): merge by normalized URL; returns "Added N, skipped M". */
    suspend fun importJson(text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = graph.provisioning.importRecords(text)
            val parts = mutableListOf("Added ${result.added}")
            if (result.skipped > 0) parts += "skipped ${result.skipped} (already here)"
            Result.success(parts.joinToString(", "))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<String> {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }
        return when {
            text == null -> Result.failure(IllegalArgumentException("Couldn't read that file"))
            else -> importJson(text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenGateways: () -> Unit,
    vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HermesBotsApp
                SettingsViewModel(app)
            }
        },
    ),
) {
    val themeMode by vm.themeMode.collectAsState()
    val notificationsEnabled by vm.notificationsEnabled.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(feedback) { feedback?.let { snackbar.showSnackbar(it); feedback = null } }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                vm.importFromUri(uri).fold(
                    onSuccess = { feedback = it },
                    onFailure = { feedback = it.message ?: "Import failed" },
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = themeMode == SettingsRepository.THEME_SYSTEM,
                    onClick = { vm.setThemeMode(SettingsRepository.THEME_SYSTEM) },
                    label = { Text("System") },
                )
                FilterChip(
                    selected = themeMode == SettingsRepository.THEME_DARK,
                    onClick = { vm.setThemeMode(SettingsRepository.THEME_DARK) },
                    label = { Text("Dark") },
                )
                FilterChip(
                    selected = themeMode == SettingsRepository.THEME_LIGHT,
                    onClick = { vm.setThemeMode(SettingsRepository.THEME_LIGHT) },
                    label = { Text("Light") },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Notifications",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bot notifications", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Notify when a bot finishes while the app is backgrounded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = { vm.setNotificationsEnabled(it) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Fleet config",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ListItem(
                headlineContent = { Text("Share my gateways") },
                supportingContent = { Text("Copy every gateway — sign-ins included — so another phone can join the fleet") },
                modifier = Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        val json = vm.exportJson()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, "Hermes fleet config")
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        context.startActivity(Intent.createChooser(send, "Share fleet config"))
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Add gateways from a config") },
                supportingContent = { Text("Paste a shared fleet config, or pick its file — duplicates are skipped") },
                modifier = Modifier.fillMaxWidth().clickable { importing = true },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Gateways",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ListItem(
                headlineContent = { Text("Manage gateways") },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenGateways),
            )
        }
    }

    if (importing) {
        ImportFleetDialog(
            onDismiss = { importing = false },
            onPickFile = {
                importing = false
                filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            onImport = { text ->
                importing = false
                scope.launch {
                    vm.importJson(text).fold(
                        onSuccess = { feedback = it },
                        onFailure = { feedback = it.message ?: "Import failed" },
                    )
                }
            },
        )
    }
}

@Composable
private fun ImportFleetDialog(
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add gateways") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a shared fleet config — sign-ins come with it and stay on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Fleet config JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                TextButton(onClick = onPickFile) { Text("Pick a file instead…") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank(),
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
