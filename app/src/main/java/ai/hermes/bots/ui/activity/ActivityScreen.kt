package ai.hermes.bots.ui.activity

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.NotificationEntry
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.ui.components.FaceAvatar
import ai.hermes.bots.ui.components.FaceState
import ai.hermes.bots.ui.theme.Dimens
import ai.hermes.bots.ui.theme.HermesTheme
import ai.hermes.bots.ui.util.Humanize
import android.animation.ValueAnimator
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay

/** Action-first Pulse console backed entirely by repository state. */
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

  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(30_000)
      tick++
    }
  }
  val nowMs = remember(tick) { System.currentTimeMillis() }
  val labels = remember(connectionLabels) { connectionLabels.associate { it.id to it.label } }
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(error) {
    val message = error ?: return@LaunchedEffect
    snackbar.showSnackbar(message)
    vm.consumeError()
  }

  // Expired prompts remain in the repository for history but are no longer actionable Pulse items.
  val needsRows = remember(pending) { ActivitySections.needsYou(pending.filterNot { it.expired }) }
  val workingRows = remember(roster) { ActivitySections.liveNow(roster) }
  val upcomingRows = remember(jobs) { ActivitySections.upcoming(jobs) }
  val recentRows = remember(history) { ActivitySections.recent(history) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title, style = HermesTheme.typography.screenTitle) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
          onBack?.let { callback ->
            IconButton(onClick = callback) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
          }
        },
        actions = { TextButton(onClick = onOpenHistory) { Text("History") } },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
    containerColor = HermesTheme.colors.canvas,
  ) { padding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        contentPadding = PaddingValues(
          start = Dimens.ScreenGutter,
          end = Dimens.ScreenGutter,
          bottom = Dimens.BottomContentClearance,
        ),
      ) {
      item(key = "header-needs") { SectionTitle("Needs you") }
      if (needsRows.isEmpty()) {
        item(key = "empty-needs") { EmptyLine("All clear — nothing is waiting on you.") }
      } else {
        items(needsRows, key = { "need-${it.pending.card.requestId}" }) { row ->
          NeedsYouCard(
            row = row,
            machineLabel = labels[row.pending.connectionId],
            nowMs = nowMs,
            onRespond = { choice -> vm.respond(row.pending, choice) },
            onOpen = {
              row.pending.botName?.takeIf { it.isNotBlank() }?.let {
                onOpenChat(row.pending.connectionId, it)
              }
            },
          )
          Spacer(Modifier.height(8.dp))
        }
      }

      item(key = "header-working") { SectionTitle(if (showUpcoming) "Live now" else "Working now") }
      if (workingRows.isEmpty()) {
        item(key = "empty-working") { EmptyLine("No bots are working right now.") }
      } else {
        item(key = "working-rail") {
          LazyRow(
            contentPadding = PaddingValues(end = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(workingRows, key = { "working-${it.bot.connectionId}:${it.bot.name}" }) { entry ->
              WorkingCard(
                entry = entry,
                machineLabel = labels[entry.bot.connectionId],
                avatar = avatars[entry.avatarKey],
                onOpen = { onOpenChat(entry.bot.connectionId, entry.bot.name) },
              )
            }
          }
        }
      }

      if (showUpcoming) {
        item(key = "header-upcoming") { SectionTitle("Upcoming") }
        if (upcomingRows.isEmpty()) {
          item(key = "empty-upcoming") { EmptyLine("No routines scheduled — add one from a bot's chat.") }
        } else {
          items(upcomingRows, key = { "up-${it.connectionId}:${it.jobId}" }) { row ->
            UpcomingRowView(row = row, nowMs = nowMs)
          }
        }
      }

      item(key = "header-recent") { SectionTitle("Recent") }
      if (recentRows.isEmpty()) {
        item(key = "empty-recent") { EmptyLine("No recent activity yet.") }
      } else {
        items(recentRows, key = { "rec-${it.atMs}:${it.sessionId}" }) { entry ->
          RecentRow(
            entry = entry,
            avatar = entry.recentAvatarKey?.let { avatars[it] },
            machineLabel = entry.connectionId?.let(labels::get),
            nowMs = nowMs,
            onOpen = { entry.chatTarget?.let { (conn, bot) -> onOpenChat(conn, bot) } },
          )
        }
      }
      }
    }
  }
}

private val RosterEntry.avatarKey: String
  get() = bot.connectionId + ":" + bot.name

private val NotificationEntry.chatTarget: Pair<String, String>?
  get() = connectionId?.takeIf { it.isNotBlank() }?.let { conn ->
    botName?.takeIf { it.isNotBlank() }?.let { conn to it }
  }

private val NotificationEntry.recentAvatarKey: String?
  get() = chatTarget?.let { (conn, bot) -> "$conn:$bot" }

@Composable
private fun SectionTitle(label: String) {
  Text(
    text = label,
    style = HermesTheme.typography.sectionTitle,
    color = HermesTheme.colors.text,
    modifier = Modifier.padding(top = Dimens.GapXl, bottom = Dimens.GapSm),
  )
}

@Composable
private fun EmptyLine(text: String) {
  Text(
    text = text,
    style = HermesTheme.typography.bodySmall,
    color = HermesTheme.colors.textMuted,
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
  )
}

@Composable
private fun NeedsYouCard(
  row: ActivitySections.NeedsYouRow,
  machineLabel: String?,
  nowMs: Long,
  onRespond: (String) -> Unit,
  onOpen: () -> Unit,
) {
  val pending = row.pending
  val attention = HermesTheme.colors.attention
  val botLabel = pending.botName?.takeIf { it.isNotBlank() } ?: "A bot"
  val need = when (pending.card.kind.substringBefore('.').lowercase()) {
    "clarify" -> "clarification"
    "secret" -> "a secret response"
    "sudo" -> "approval"
    else -> "approval"
  }
  Surface(
    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    shape = RoundedCornerShape(15.dp),
    color = HermesTheme.colors.surface,
    border = BorderStroke(1.dp, attention.copy(alpha = 0.35f)),
  ) {
    Row(
      modifier = Modifier
        .clickable(enabled = pending.botName?.isNotBlank() == true, onClick = onOpen)
        .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(Modifier.size(width = 5.dp, height = 36.dp).background(attention, RoundedCornerShape(3.dp)))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "$botLabel needs $need",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = listOfNotNull(machineLabel?.takeIf { it.isNotBlank() }, ActivitySections.age(pending.atMs, nowMs))
            .joinToString(" · "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (pending.botName.isNullOrBlank()) {
          Text(
            text = "Bot still resolving…",
            style = HermesTheme.typography.metadata,
            color = HermesTheme.colors.textMuted,
          )
        } else if (row.quickActions.isEmpty()) {
          Text(
            text = "Open ${pending.botName}'s chat to respond",
            style = HermesTheme.typography.label,
            color = HermesTheme.colors.textMuted,
          )
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            row.quickActions.forEachIndexed { index, choice ->
              if (index == 0) {
                Button(
                  onClick = { onRespond(choice) },
                  modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                  colors = ButtonDefaults.buttonColors(
                    containerColor = attention,
                    contentColor = HermesTheme.colors.canvasDeep,
                  ),
                  contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text(choice) }
              } else {
                OutlinedButton(
                  onClick = { onRespond(choice) },
                  modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                  border = BorderStroke(1.dp, attention.copy(alpha = 0.7f)),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = attention),
                  contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text(choice) }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WorkingCard(
  entry: RosterEntry,
  machineLabel: String?,
  avatar: AvatarImage?,
  onOpen: () -> Unit,
) {
  Surface(
    onClick = onOpen,
    modifier = Modifier.width(148.dp).heightIn(min = 116.dp),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        FaceAvatar(
          entry.bot.name,
          28.dp,
          real = avatar,
          state = FaceState.Working,
        )
        Column(Modifier.weight(1f)) {
          Text(
            text = entry.bot.displayName ?: entry.bot.name,
            style = HermesTheme.typography.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = machineLabel ?: entry.bot.connectionId,
            style = HermesTheme.typography.metadata,
            color = HermesTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Text(
        text = entry.bot.lastPreview?.takeIf { it.isNotBlank() }
          ?: Humanize.model(entry.bot.model)?.let { "Working · $it" }
          ?: "Working now",
        style = HermesTheme.typography.bodySmall,
        color = HermesTheme.colors.textMuted,
        minLines = 2,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      WorkingRail()
    }
  }
}

@Composable
private fun WorkingRail() {
  val success = HermesTheme.colors.success
  val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .height(3.dp)
      .clip(RoundedCornerShape(2.dp))
      .background(HermesTheme.colors.line),
  ) {
    val segmentWidth = maxWidth * 0.35f
    if (reducedMotion) {
      Box(Modifier.width(segmentWidth).height(3.dp).background(success, RoundedCornerShape(2.dp)))
    } else {
      val transition = rememberInfiniteTransition(label = "working-rail")
      val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Restart),
        label = "working-rail-position",
      )
      Box(
        Modifier
          .offset(x = (maxWidth + segmentWidth) * fraction - segmentWidth)
          .width(segmentWidth)
          .height(3.dp)
          .background(success, RoundedCornerShape(2.dp)),
      )
    }
  }
}

@Composable
private fun UpcomingRowView(row: ActivitySections.UpcomingRow, nowMs: Long) {
  val nextLabel = ActivitySections.nextRunLabel(row.nextRunAtMs, nowMs)
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 7.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        text = row.name,
        style = HermesTheme.typography.machineName,
        color = if (row.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = nextLabel?.let { "${row.cadence} · $it" } ?: row.cadence,
        style = HermesTheme.typography.bodySmall,
        color = HermesTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (!row.enabled) {
      Text("Paused", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun RecentRow(
  entry: NotificationEntry,
  avatar: AvatarImage?,
  machineLabel: String?,
  nowMs: Long,
  onOpen: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 58.dp)
      .clickable(enabled = entry.chatTarget != null, onClick = onOpen)
      .padding(horizontal = 7.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    FaceAvatar(
      entry.botName?.takeIf { it.isNotBlank() } ?: "notification:${entry.sessionId}",
      40.dp,
      real = avatar,
    )
    Column(Modifier.weight(1f)) {
      Text(
        text = entry.botLabel,
        style = HermesTheme.typography.botName,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = entry.preview,
        style = HermesTheme.typography.bodySmall,
        color = HermesTheme.colors.textMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = listOfNotNull(machineLabel?.takeIf { it.isNotBlank() }, ActivitySections.age(entry.atMs, nowMs))
          .joinToString(" · "),
        style = HermesTheme.typography.metadata,
        color = HermesTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
