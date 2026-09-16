package ai.hermes.bots.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
data class NotificationEntry(
    val botLabel: String,
    val preview: String,
    val sessionId: String,
    val atMs: Long,
    // Optional chat target so history rows can deep-link back (SV-15); null keeps
    // pre-existing records decodable and their rows non-clickable.
    val connectionId: String? = null,
    val botName: String? = null,
)

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyNotificationsEnabled = booleanPreferencesKey("notifications_enabled")
    private val keyNotificationHistory = stringPreferencesKey("notification_history_json")
    private val keyHistorySeenAt = longPreferencesKey("history_seen_at")
    private val keyBubbleMode = booleanPreferencesKey("bubble_mode")
    private val keyMachineExpansion = stringPreferencesKey("machine_expansion_json")

    val themeMode: StateFlow<String> = context.settingsStore.data
        .map { prefs ->
            prefs[keyThemeMode]?.takeIf { it in THEME_MODES } ?: THEME_SYSTEM
        }
        .stateIn(scope, SharingStarted.Eagerly, THEME_SYSTEM)

    val notificationsEnabled: StateFlow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[keyNotificationsEnabled] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val notificationHistory: StateFlow<List<NotificationEntry>> = context.settingsStore.data
        .map { prefs -> decodeHistory(prefs[keyNotificationHistory]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Bell watermark (SV-14): the badge lights only for history newer than this stamp. */
    val historySeenAt: StateFlow<Long> = context.settingsStore.data
        .map { prefs -> prefs[keyHistorySeenAt] ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Bubble Mode (UI-SPEC.md §4.2 rev): iMessage-style Bot Chat transcript; default ON. */
    val bubbleMode: StateFlow<Boolean> = context.settingsStore.data
        .map { prefs -> prefs[keyBubbleMode] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Explicit Fleet disclosure choices, keyed by stable gateway connection ID. */
    val machineExpansionOverrides: StateFlow<Map<String, Boolean>> = context.settingsStore.data
        .map { prefs -> decodeMachineExpansion(prefs[keyMachineExpansion]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    suspend fun setThemeMode(mode: String) {
        if (mode !in THEME_MODES) return
        context.settingsStore.edit { prefs -> prefs[keyThemeMode] = mode }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsStore.edit { prefs -> prefs[keyNotificationsEnabled] = enabled }
    }

    /** Persists the chat overflow "Bubble mode" toggle; survives process death (checklist 11). */
    suspend fun setBubbleMode(enabled: Boolean) {
        context.settingsStore.edit { prefs -> prefs[keyBubbleMode] = enabled }
    }

    suspend fun setMachineExpanded(connectionId: String, expanded: Boolean) {
        context.settingsStore.edit { prefs ->
            val next = decodeMachineExpansion(prefs[keyMachineExpansion]).toMutableMap()
            next[connectionId] = expanded
            prefs[keyMachineExpansion] = json.encodeToString(next)
        }
    }

    /** Stamps the seen watermark; called when the Notifications screen is opened (SV-14). */
    suspend fun markHistorySeen(atMs: Long = System.currentTimeMillis()) {
        context.settingsStore.edit { prefs -> prefs[keyHistorySeenAt] = atMs }
    }

    /** Newest first, capped at HISTORY_LIMIT. In-app history records even suppressed notifications. */
    suspend fun appendNotification(
        botLabel: String,
        preview: String,
        sessionId: String,
        connectionId: String? = null,
        botName: String? = null,
    ) {
        context.settingsStore.edit { prefs ->
            val entry = NotificationEntry(
                botLabel = botLabel,
                preview = preview,
                sessionId = sessionId,
                atMs = System.currentTimeMillis(),
                connectionId = connectionId,
                botName = botName,
            )
            val next = (listOf(entry) + decodeHistory(prefs[keyNotificationHistory])).take(HISTORY_LIMIT)
            prefs[keyNotificationHistory] = json.encodeToString(next)
        }
    }

    suspend fun clearNotifications() {
        context.settingsStore.edit { prefs -> prefs[keyNotificationHistory] = "[]" }
    }

    private fun decodeHistory(text: String?): List<NotificationEntry> =
        text?.let { runCatching { json.decodeFromString<List<NotificationEntry>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()

    private fun decodeMachineExpansion(text: String?): Map<String, Boolean> =
        text?.let { runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrDefault(emptyMap()) }
            ?: emptyMap()

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        val THEME_MODES = setOf(THEME_SYSTEM, THEME_DARK, THEME_LIGHT)
        const val HISTORY_LIMIT = 50
    }
}
