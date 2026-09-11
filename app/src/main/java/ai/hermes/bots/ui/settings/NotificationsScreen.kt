package ai.hermes.bots.ui.settings

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.NotificationEntry
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.ui.components.FaceAvatar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenChat: (connectionId: String, botName: String) -> Unit = { _, _ -> },
    vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ai.hermes.bots.HermesBotsApp
                SettingsViewModel(app)
            }
        },
    ),
) {
    val history by vm.notificationHistory.collectAsState()
    val roster by vm.roster.collectAsState()
    val avatars by vm.avatars.collectAsState()

    // Opening the screen stamps the seen watermark, so the roster bell badge clears (SV-14).
    val app = LocalContext.current.applicationContext as ai.hermes.bots.HermesBotsApp
    LaunchedEffect(Unit) { app.graph.settings.markHistorySeen() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { vm.clearNotifications() }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No notifications yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        val resolved = remember(history, roster) { resolveNotifications(history, roster) }
        val rows = buildRows(resolved)
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is NotifRow.Header -> DayHeader(row.label)
                    is NotifRow.Item -> NotificationRow(row.item, avatars[row.item.avatarKey], onOpenChat)
                }
            }
        }
    }
}

private sealed interface NotifRow {
    val key: String

    data class Header(val label: String, val epochDay: Long) : NotifRow {
        override val key: String = "day-$epochDay"
    }

    data class Item(val item: ResolvedNotification) : NotifRow {
        override val key: String =
            item.entry.atMs.toString() + ":" + item.entry.sessionId + ":" + item.entry.preview.hashCode()
    }
}

/** A history entry with its bot identity resolved against the live roster (V3).
 *  Unresolved rows (worker-session events) must not claim a false identity: they render
 *  as a neutral "Bot finished" line with a muted session-seeded avatar. */
private data class ResolvedNotification(
    val entry: NotificationEntry,
    val label: String,
    val avatarKey: String?,
    val resolved: Boolean,
)

/**
 * Display-time bot resolution (V3): history rows recorded with the app-name fallback
 * ("Hermes Bots") resolve against the roster — first by bot name/display name, then by
 * any known session-id form on the bot (canonical_session.id). Events are NOT re-written;
 * rows nothing matches keep their recorded label.
 */
private fun resolveNotifications(
    history: List<NotificationEntry>,
    roster: List<RosterEntry>,
): List<ResolvedNotification> {
    if (roster.isEmpty()) {
        return history.map { entry ->
            val fallback = entry.botLabel.trim().equals(FALLBACK_LABEL, ignoreCase = true)
            ResolvedNotification(entry, entry.botLabel, null, resolved = !fallback)
        }
    }
    val bySession = HashMap<String, RosterEntry>()
    val byName = HashMap<String, RosterEntry>()
    roster.forEach { e ->
        e.bot.canonicalSessionId?.let { sid -> if (sid.isNotBlank()) bySession[sid] = e }
        byName[e.bot.name.lowercase()] = e
        e.bot.displayName?.takeIf { it.isNotBlank() }?.let { byName[it.lowercase()] = e }
    }
    return history.map { entry ->
        val byLabel = byName[entry.botLabel.trim().lowercase()]
        val match = byLabel ?: bySession[entry.sessionId]
        if (match != null) {
            ResolvedNotification(
                entry,
                match.bot.displayName ?: match.bot.name,
                match.bot.connectionId + ":" + match.bot.name,
                resolved = true,
            )
        } else {
            ResolvedNotification(entry, entry.botLabel, null, resolved = false)
        }
    }
}

private const val FALLBACK_LABEL = "Hermes Bots"
private const val UNRESOLVED_TITLE = "Bot finished"

/** Newest-first history with day headers between calendar days (audit A22). */
private fun buildRows(
    history: List<ResolvedNotification>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<NotifRow> {
    val out = mutableListOf<NotifRow>()
    var lastDay: LocalDate? = null
    history.forEach { item ->
        val day = Instant.ofEpochMilli(item.entry.atMs).atZone(zone).toLocalDate()
        if (day != lastDay) {
            out += NotifRow.Header(dayLabel(day, today), day.toEpochDay())
            lastDay = day
        }
        out += NotifRow.Item(item)
    }
    return out
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
}

@Composable
private fun DayHeader(label: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotificationRow(
    item: ResolvedNotification,
    avatar: AvatarImage?,
    onOpenChat: (connectionId: String, botName: String) -> Unit,
) {
    // Rows recorded with a chat target are tappable; legacy entries (no ids) stay inert.
    val chatTarget = item.entry.connectionId
        ?.takeIf { it.isNotBlank() }
        ?.let { conn -> item.entry.botName?.takeIf { it.isNotBlank() }?.let { conn to it } }
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (chatTarget != null) {
                    Modifier.clickable { chatTarget?.let { (conn, bot) -> onOpenChat(conn, bot) } }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (item.resolved) {
            FaceAvatar(item.label, 40.dp, real = avatar)
        } else {
            // Muted neutral: session-seeded face at low alpha reads as "unattributed bot event".
            FaceAvatar(
                item.entry.sessionId.ifBlank { "?" },
                40.dp,
                modifier = Modifier.alpha(0.55f),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (item.resolved) item.label else UNRESOLVED_TITLE,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                item.entry.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Text(
            shortTime(item.entry.atMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun shortTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - ms).coerceAtLeast(0)
    return when {
        delta < 60_000L -> "now"
        delta < 3_600_000L -> String.format(Locale.ROOT, "%dm", delta / 60_000L)
        delta < 86_400_000L -> String.format(Locale.ROOT, "%dh", delta / 3_600_000L)
        else -> String.format(Locale.ROOT, "%dd", delta / 86_400_000L)
    }
}
