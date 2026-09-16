package ai.hermes.bots.ui.activity

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.NotificationEntry
import ai.hermes.bots.data.PendingApproval
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.ui.components.FaceAvatar
import ai.hermes.bots.ui.theme.BotAccent
import ai.hermes.bots.ui.theme.LocalBrandDark
import ai.hermes.bots.ui.theme.brandPalette
import ai.hermes.bots.ui.util.Humanize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay

/**
 * Activity console (UI-SPEC.md §4.6, opened from the roster bell): Needs you / Live now /
 * Upcoming / Recent, designed empties, per-bot accent dots, dark+light safe. Works from
 * the last snapshot when gateways are offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
  onBack: (() -> Unit)? = null,
  onOpenChat: (connectionId: String, botName: String) -> Unit,
  onOpenRoutines: (connectionId: String, botName: String) -> Unit,
  onOpenHistory: () -> Unit,
  title: String = "Activity",
  showUpcoming: Boolean = true,
  vm: ActivityViewModel = viewModel(
    factory = viewModelFactory {
      initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ai.hermes.bots.HermesBotsApp
        ActivityViewModel(app)
      }
    },
  ),
) {
  val pending by vm.pending.collectAsState()
  val roster by vm.roster.collectAsState()
  val avatars by vm.avatars.collectAsState()
  val jobs by vm.jobs.collectAsState()
  val history by vm.history.collectAsState()
  val connectionLabels by vm.connectionLabels.collectAsState()
  val error by vm.error.collectAsState()
  val dark = LocalBrandDark.current

  // Ages and "next run" labels stay honest without a per-row ticker (§3.2 budget).
  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(30_000)
      tick++
    }
  }
  val nowMs = remember(tick) { System.currentTimeMillis() }

  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(error) {
    val message = error ?: return@LaunchedEffect
    snackbar.showSnackbar(message)
    vm.consumeError()
  }

  val needsRows = remember(pending) { ActivitySections.needsYou(pending) }
  val liveRows = remember(roster) { ActivitySections.liveNow(roster) }
  val upcomingRows = remember(jobs) { ActivitySections.upcoming(jobs) }
  val recentRows = remember(history) { ActivitySections.recent(history) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = { onBack?.let { callback ->
          IconButton(onClick = callback) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        } },
        actions = {
          TextButton(onClick = onOpenHistory) { Text("History") }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
    containerColor = MaterialTheme.colorScheme.background,
  ) { padding ->
    LazyColumn(
      Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        bottom = 24.dp,
      ),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      item(key = "header-needs") { SectionHeader("Needs you") }
      if (needsRows.isEmpty()) {
        item(key = "empty-needs") { EmptyLine("All clear — nothing is waiting on you.") }
      } else {
        items(needsRows, key = { "need-" + it.pending.card.requestId }) { row ->
          NeedsYouRow(
            row = row,
            avatar = avatars[row.pending.avatarKey],
            machineLabel = connectionLabels.firstOrNull { it.id == row.pending.connectionId }?.label,
            nowMs = nowMs,
            dark = dark,
            onRespond = { choice -> vm.respond(row.pending, choice) },
            onOpen = {
              row.pending.botName?.takeIf { it.isNotBlank() }?.let {
                onOpenChat(row.pending.connectionId, it)
              }
            },
          )
        }
      }

      item(key = "header-live") { SectionHeader(if (showUpcoming) "Live now" else "Working now") }
      if (liveRows.isEmpty()) {
        item(key = "empty-live") { EmptyLine("No bots are working right now.") }
      } else {
        items(liveRows, key = { "live-" + it.bot.connectionId + ":" + it.bot.name }) { entry ->
          LiveNowRow(entry = entry, machineLabel = connectionLabels.firstOrNull { it.id == entry.bot.connectionId }?.label, avatar = avatars[entry.avatarKey], onOpen = {
            onOpenChat(entry.bot.connectionId, entry.bot.name)
          })
        }
      }

      if (showUpcoming) {
        item(key = "header-upcoming") { SectionHeader("Upcoming") }
        if (upcomingRows.isEmpty()) {
          item(key = "empty-upcoming") { EmptyLine("No routines scheduled — add one from a bot's chat.") }
        } else {
          items(upcomingRows, key = { "up-" + it.connectionId + ":" + it.jobId }) { row ->
            UpcomingRowView(row = row, nowMs = nowMs, onOpen = null)
          }
        }
      }

      item(key = "header-recent") { SectionHeader("Recent") }
      if (recentRows.isEmpty()) {
        item(key = "empty-recent") { EmptyLine("No recent activity yet.") }
      } else {
        items(recentRows, key = { "rec-" + it.atMs + ":" + it.sessionId }) { entry ->
          RecentRow(
            entry = entry,
            avatar = entry.recentAvatarKey?.let { avatars[it] },
            machineLabel = entry.connectionId?.let { id ->
              connectionLabels.firstOrNull { it.id == id }?.label
            },
            onOpen = {
              entry.chatTarget?.let { (conn, bot) -> onOpenChat(conn, bot) }
            },
          )
        }
      }
    }
  }
}

private val PendingApproval.avatarKey: String?
  get() = botName?.takeIf { it.isNotBlank() }?.let { "$connectionId:$it" }

private val RosterEntry.avatarKey: String
  get() = bot.connectionId + ":" + bot.name

private val NotificationEntry.chatTarget: Pair<String, String>?
  get() = connectionId?.takeIf { it.isNotBlank() }?.let { conn ->
    botName?.takeIf { it.isNotBlank() }?.let { conn to it }
  }

private val NotificationEntry.recentAvatarKey: String?
  get() = chatTarget?.let { (conn, bot) -> "$conn:$bot" }

@Composable
private fun SectionHeader(label: String) {
  Text(
    label,
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
  )
}

@Composable
private fun EmptyLine(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
  )
}

/** Small deterministic per-bot accent dot (UI-SPEC.md §4.6 "per-bot accent dots"). */
@Composable
private fun AccentDot(name: String, dark: Boolean) {
  Box(
    Modifier
      .size(8.dp)
      .background(BotAccent.color(name, dark), CircleShape),
  )
}

@Composable
private fun NeedsYouRow(
  row: ActivitySections.NeedsYouRow,
  avatar: AvatarImage?,
  machineLabel: String?,
  nowMs: Long,
  dark: Boolean,
  onRespond: (String) -> Unit,
  onOpen: () -> Unit,
) {
  val pending = row.pending
  val openable = pending.botName?.isNotBlank() == true && !pending.expired
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .then(if (openable) Modifier.clickable(onClick = onOpen) else Modifier)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    val faceName = pending.botName?.takeIf { it.isNotBlank() } ?: pending.sessionId ?: "?"
    FaceAvatar(
      faceName,
      44.dp,
      modifier = if (pending.expired) Modifier.alpha(0.5f) else Modifier,
      real = avatar,
    )
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          row.kindLabel,
          style = MaterialTheme.typography.labelSmall,
          color = if (pending.expired) {
            MaterialTheme.colorScheme.onSurfaceVariant
          } else {
            MaterialTheme.colorScheme.primary
          },
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          ActivitySections.age(pending.atMs, nowMs),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        pending.card.command ?: "Wants your response",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      machineLabel?.takeIf { it.isNotBlank() }?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (pending.expired) {
        Text(
          "Expired",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else if (pending.botName.isNullOrBlank()) {
        Text(
          "Bot still resolving…",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else if (row.quickActions.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          row.quickActions.forEachIndexed { index, choice ->
            val destructive = choice.equals("deny", ignoreCase = true)
            val contentColor = if (destructive) brandPalette().danger else Color.Unspecified
            if (index == 0) {
              FilledTonalButton(
                onClick = { onRespond(choice) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.filledTonalButtonColors(contentColor = contentColor),
              ) { Text(choice) }
            } else {
              OutlinedButton(
                onClick = { onRespond(choice) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
              ) { Text(choice) }
            }
          }
        }
      } else {
        // Q11 (QA 2026-09-14): no payload choices (free-text clarify) — never fabricate
        // quick actions; point at the bot's chat (the row itself is already tappable).
        Text(
          "Open ${pending.botName}'s chat to respond",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun LiveNowRow(entry: RosterEntry, machineLabel: String?, avatar: AvatarImage?, onOpen: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .clickable(onClick = onOpen)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    FaceAvatar(entry.bot.displayName ?: entry.bot.name, 44.dp, real = avatar)
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AccentDot(entry.bot.displayName ?: entry.bot.name, LocalBrandDark.current)
        Text(
          entry.bot.displayName ?: entry.bot.name,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        Humanize.model(entry.bot.model)?.let { "Working · $it" } ?: "Working now",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      machineLabel?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun UpcomingRowView(row: ActivitySections.UpcomingRow, nowMs: Long, onOpen: (() -> Unit)?) {
  val nextLabel = ActivitySections.nextRunLabel(row.nextRunAtMs, nowMs)
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        row.name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (row.enabled) {
          MaterialTheme.colorScheme.onSurface
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        nextLabel?.let { "${row.cadence} · $it" } ?: row.cadence,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (!row.enabled) {
      Text(
        "Paused",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun RecentRow(
  entry: NotificationEntry,
  avatar: AvatarImage?,
  machineLabel: String?,
  onOpen: () -> Unit,
) {
  val openable = entry.chatTarget != null
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .then(if (openable) Modifier.clickable(onClick = onOpen) else Modifier)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    FaceAvatar(entry.botLabel.ifBlank { "?" }, 40.dp, real = avatar)
    Column(Modifier.weight(1f)) {
      Text(
        entry.botLabel,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        entry.preview,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      machineLabel?.takeIf { it.isNotBlank() }?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(Modifier.width(4.dp))
    Text(
      ActivitySections.age(entry.atMs, System.currentTimeMillis()),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
