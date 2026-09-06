package ai.hermes.bots.ui.settings

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.NotificationEntry
import ai.hermes.bots.data.SettingsRepository
import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as HermesBotsApp).graph.settings

    val themeMode: StateFlow<String> = settings.themeMode
    val notificationsEnabled: StateFlow<Boolean> = settings.notificationsEnabled
    val notificationHistory: StateFlow<List<NotificationEntry>> = settings.notificationHistory

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setNotificationsEnabled(enabled) }
    }

    fun clearNotifications() {
        viewModelScope.launch { settings.clearNotifications() }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
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
}
