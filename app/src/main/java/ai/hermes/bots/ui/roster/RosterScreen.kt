package ai.hermes.bots.ui.roster

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.BotRow
import ai.hermes.bots.data.MergedBot
import ai.hermes.bots.protocol.SocketState
import ai.hermes.bots.ui.components.FaceAvatar
import ai.hermes.bots.ui.components.FaceState
import ai.hermes.bots.ui.components.PulsingDot
import ai.hermes.bots.ui.theme.BotAccent
import ai.hermes.bots.ui.theme.Dimens
import ai.hermes.bots.ui.theme.LocalBrandDark
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterScreen(
    onOpenChat: (connectionId: String, botName: String) -> Unit,
    onOpenGateways: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGroups: () -> Unit,
    onNewBot: () -> Unit,
    onEditBot: (connectionId: String, botName: String) -> Unit,
    onOpenRoutines: (connectionId: String, botName: String) -> Unit,
    onOpenAnyChat: () -> Unit = {},
    vm: RosterViewModel = viewModel(),
) {
    val fleet by vm.merged.collectAsState()
    val avatars by vm.avatars.collectAsState()
    val hasNotifications by vm.hasNotifications.collectAsState()
    val connections by vm.connections.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val socketStates by vm.socketStates.collectAsState()
    val transientError by vm.transientError.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // Resolved dark flag (in-app theme override wins over system) — per-bot accents (§3.1)
    // must follow the same theme the user picked.
    val darkTheme = LocalBrandDark.current
    var search by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<MergedBot?>(null) }
    var sectionFor by remember { mutableStateOf<MergedBot?>(null) }
    val searchFocus = remember { FocusRequester() }

    // SV-11: hide / move-to-section failures surface here instead of vanishing.
    LaunchedEffect(transientError) {
        transientError?.let {
            snackbar.showSnackbar(it)
            vm.consumeTransientError()
        }
    }

    val hiddenCount = fleet.rows.count { it.hidden }
    val query = search.trim().lowercase(Locale.ROOT)
    val visible = fleet.rows
        .filter { if (showHidden) it.hidden else !it.hidden }
        .filter { m ->
            if (query.isEmpty()) true
            else m.name.lowercase(Locale.ROOT).contains(query) ||
                (m.displayName ?: "").lowercase(Locale.ROOT).contains(query) ||
                (m.primary.bot.description ?: "").lowercase(Locale.ROOT).contains(query) ||
                (m.preview ?: "").lowercase(Locale.ROOT).contains(query)
        }
    // Rows are per-gateway: a chip appears only when the same bot name lives on several
    // gateways, labeling which one this row is.
    val collidedNames = fleet.rows
        .groupBy { it.name.lowercase(Locale.ROOT) }
        .filterValues { it.size > 1 }
        .keys
    fun chipLabel(m: MergedBot): String? =
        if (m.name.lowercase(Locale.ROOT) !in collidedNames) null
        else connections.firstOrNull { it.id == m.primary.bot.connectionId }?.label
    val listed = visible.filterNot { it == fleet.pinnedAssistant }
    val sections = listed.groupBy { it.sectionId }.toSortedMap(compareBy { it ?: "" })

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Bots") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { searchFocus.requestFocus() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenNotifications) {
                        BadgedBox(
                            badge = { if (hasNotifications) Badge() },
                        ) {
                            Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                        }
                    }
                    IconButton(onClick = onNewBot) {
                        Icon(Icons.Filled.Add, contentDescription = "New bot")
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Any chats") },
                            onClick = { menu = false; onOpenAnyChat() },
                        )
                        DropdownMenuItem(
                            text = { Text("Group chats") },
                            onClick = { menu = false; onOpenGroups() },
                        )
                        DropdownMenuItem(
                            text = { Text("Gateways") },
                            onClick = { menu = false; onOpenGateways() },
                        )
                        DropdownMenuItem(
                            text = { Text("App settings") },
                            onClick = { menu = false; onOpenSettings() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = Dimens.GutterScreen)) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .heightIn(min = 40.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = search,
                            onValueChange = { search = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFocus)
                                .padding(vertical = 10.dp),
                            decorationBox = { inner ->
                                androidx.compose.foundation.layout.Box {
                                    if (search.isEmpty()) {
                                        Text(
                                            "Search bots and group chats",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }
                val active = fleet.rows.filter { it.activeNow }
                if (active.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "Active now",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            active.forEach { m ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable {
                                            vm.markRead(m.primary.bot.connectionId, m.name)
                                            onOpenChat(m.primary.bot.connectionId, m.name)
                                        }
                                        .padding(4.dp),
                                ) {
                                    Box {
                                        FaceAvatar(
                                            m.name,
                                            56.dp,
                                            real = avatars[avatarKey(m.primary.bot)],
                                            state = FaceState.Working,
                                            a11yLabel = "${m.displayName ?: m.name}, working",
                                        )
                                        PulsingDot(
                                            dotSize = 12.dp,
                                            color = BotAccent.color(m.name, darkTheme),
                                            borderColor = MaterialTheme.colorScheme.background,
                                            modifier = Modifier.align(Alignment.BottomEnd),
                                        )
                                    }
                                    Text(
                                        m.displayName ?: m.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(64.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (hiddenCount > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FilterChip(
                            selected = showHidden,
                            onClick = { showHidden = !showHidden },
                            label = { Text("Hidden ($hiddenCount)") },
                        )
                    }
                }
                if (visible.isEmpty()) {
                    if (showHidden) {
                        Text(
                            "No hidden bots",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(8.dp),
                        )
                    } else if (connections.isNotEmpty() && connections.none { socketStates[it.id] is SocketState.Ready }) {
                        // SV-17: gateways configured but none reachable — don't claim the
                        // roster is empty, point the user at the thing they can fix.
                        ai.hermes.bots.ui.components.EmptyState(
                            title = "Can't reach your gateways right now.",
                            body = "Your bots will appear as soon as a gateway connects.",
                            cta = {
                                Button(onClick = onOpenGateways) { Text("Open Gateways") }
                            },
                        )
                    } else {
                        ai.hermes.bots.ui.components.EmptyState(
                            title = "No bots yet",
                            body = "Add one, or check Gateways.",
                            avatar = {
                                androidx.compose.foundation.layout.Box(Modifier.size(96.dp)) {
                                    ai.hermes.bots.ui.components.FaceAvatar(
                                        "hermes-roster-a",
                                        56.dp,
                                        modifier = Modifier.align(Alignment.CenterStart),
                                    )
                                    ai.hermes.bots.ui.components.FaceAvatar(
                                        "hermes-roster-b",
                                        56.dp,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                    ai.hermes.bots.ui.components.FaceAvatar(
                                        "hermes-roster-c",
                                        56.dp,
                                        modifier = Modifier.align(Alignment.CenterEnd),
                                    )
                                }
                            },
                        )
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    fleet.pinnedAssistant?.takeIf { !showHidden || it.hidden }?.let { pinned ->
                        item(key = "assistant-header") {
                            ai.hermes.bots.ui.components.SectionHeader("Assistant")
                        }
                        item(key = "assistant-${pinned.primary.bot.connectionId}") {
                            MergedBotRowItem(
                                modifier = Modifier.animateItem(),
                                bot = pinned,
                                avatar = avatars[avatarKey(pinned.primary.bot)],
                                chip = chipLabel(pinned),
                                onClick = {
                                    vm.markRead(pinned.primary.bot.connectionId, pinned.name)
                                    onOpenChat(pinned.primary.bot.connectionId, pinned.name)
                                },
                                onLongClick = { sheetFor = pinned },
                            )
                        }
                    }
                    sections.forEach { (sectionId, bots) ->
                        item(key = "header-${sectionId ?: "_"}") {
                            ai.hermes.bots.ui.components.SectionHeader(
                                sectionId?.replaceFirstChar { it.uppercase() } ?: "Bots",
                            )
                        }
                        itemsIndexed(bots, key = { _, m -> "${m.primary.bot.connectionId}:${m.name}" }) { _, m ->
                            MergedBotRowItem(
                                modifier = Modifier.animateItem(),
                                bot = m,
                                avatar = avatars[avatarKey(m.primary.bot)],
                                chip = chipLabel(m),
                                onClick = {
                                    vm.markRead(m.primary.bot.connectionId, m.name)
                                    onOpenChat(m.primary.bot.connectionId, m.name)
                                },
                                onLongClick = { sheetFor = m },
                            )
                        }
                    }
            }
            }
        }
    }

    sheetFor?.let { m ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Identity header — which bot am I acting on? (A23)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    FaceAvatar(
                        m.displayName ?: m.name,
                        40.dp,
                        real = avatars[avatarKey(m.primary.bot)],
                        state = faceStateOf(m),
                        a11yLabel = faceLabel(m),
                    )
                    Column {
                        Text(
                            m.displayName ?: m.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            buildString {
                                append(m.sectionId?.replaceFirstChar { it.uppercase() } ?: "Bots")
                                chipLabel(m)?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("Routines") },
                    leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    modifier = Modifier.clickable {
                        sheetFor = null
                        onOpenRoutines(m.primary.bot.connectionId, m.name)
                    },
                )
                ListItem(
                    headlineContent = { Text("Edit bot") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    modifier = Modifier.clickable {
                        sheetFor = null
                        onEditBot(m.primary.bot.connectionId, m.name)
                    },
                )
                ListItem(
                    headlineContent = { Text(if (m.hidden) "Unhide" else "Hide") },
                    leadingContent = { Icon(Icons.Filled.Clear, contentDescription = null) },
                    modifier = Modifier.clickable {
                        vm.setHidden(m.primary.bot.connectionId, m.name, !m.hidden); sheetFor = null
                    },
                )
                ListItem(
                    headlineContent = { Text("Move to section…") },
                    leadingContent = { Icon(Icons.Filled.List, contentDescription = null) },
                    modifier = Modifier.clickable { sectionFor = m; sheetFor = null },
                )
            }
        }
    }

    sectionFor?.let { m ->
        var text by remember(m) { mutableStateOf(m.sectionId.orEmpty()) }
        AlertDialog(
            onDismissRequest = { sectionFor = null },
            title = { Text("Move to section") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Section name (leave blank for Bots)") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setSection(m.primary.bot.connectionId, m.name, text.trim())
                    sectionFor = null
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { sectionFor = null }) { Text("Cancel") } },
        )
    }
}

private fun avatarKey(bot: BotRow): String = "${bot.connectionId}:${bot.name}"

// Checklist 13: every face carries a11y text "{name}, {state}"; the drawn blink/pose is
// decorative and excluded from the a11y tree (FaceAvatar handles that when a11yLabel == null).
private fun faceStateOf(bot: MergedBot): FaceState =
    if (bot.activeNow) FaceState.Working else FaceState.Idle

private fun faceLabel(bot: MergedBot): String {
    val name = bot.displayName ?: bot.name
    return when {
        bot.activeNow -> "$name, working"
        bot.unread -> "$name, unread"
        else -> "$name, idle"
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MergedBotRowItem(
    bot: MergedBot,
    avatar: AvatarImage?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    chip: String? = null,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val darkTheme = LocalBrandDark.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FaceAvatar(
                bot.name,
                48.dp,
                real = avatar,
                state = faceStateOf(bot),
                a11yLabel = faceLabel(bot),
            )
            if (bot.unread) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
            }
            // Presence lives ON the avatar (A3): working = pulsing per-bot accent dot (§3.1,
            // not brand chrome); idle = nothing. The unread dot above stays burnt orange.
            if (bot.activeNow) {
                PulsingDot(
                    dotSize = 12.dp,
                    color = BotAccent.color(bot.name, darkTheme),
                    borderColor = MaterialTheme.colorScheme.background,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    bot.displayName ?: bot.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Multi-gateway presence: primary's label + how many more host this bot.
                chip?.let { ai.hermes.bots.ui.components.ConnectionChip(it) }
            }
            Text(
                (bot.preview ?: "Tap to start the conversation")
                    .replace('*', ' ').replace('`', '\''),
                style = MaterialTheme.typography.bodySmall,
                color = if (bot.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                bot.lastActiveMs?.let { relativeTime(it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (bot.activeNow) {
                Text("active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - ms).coerceAtLeast(0)
    return when {
        delta < 60_000L -> "now"
        delta < 3_600_000L -> "${delta / 60_000L}m"
        delta < 86_400_000L -> "${delta / 3_600_000L}h"
        else -> "${delta / 86_400_000L}d"
    }
}
