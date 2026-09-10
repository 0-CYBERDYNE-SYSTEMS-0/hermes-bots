package ai.hermes.bots.ui.roster

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.ui.components.FaceAvatar
import ai.hermes.bots.ui.components.PulsingDot
import ai.hermes.bots.ui.theme.Dimens
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
    val roster by vm.roster.collectAsState()
    val avatars by vm.avatars.collectAsState()
    val hasNotifications by vm.hasNotifications.collectAsState()
    val collisions by vm.collisionNames.collectAsState()
    val connections by vm.connections.collectAsState()
    val assistant by vm.assistant.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    var search by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<RosterEntry?>(null) }
    var sectionFor by remember { mutableStateOf<RosterEntry?>(null) }
    val searchFocus = remember { FocusRequester() }

    val hiddenCount = roster.count { it.bot.hidden }
    val query = search.trim().lowercase(Locale.ROOT)
    val visible = roster
        .filter { if (showHidden) it.bot.hidden else !it.bot.hidden }
        .filter { entry ->
            if (query.isEmpty()) true
            else entry.bot.name.lowercase(Locale.ROOT).contains(query) ||
                (entry.bot.displayName ?: "").lowercase(Locale.ROOT).contains(query) ||
                (entry.bot.description ?: "").lowercase(Locale.ROOT).contains(query) ||
                (entry.bot.lastPreview ?: "").lowercase(Locale.ROOT).contains(query)
        }
    // B4: same-named bots across gateways get a quiet gateway chip.
    fun gatewayLabel(entry: RosterEntry): String? =
        if (entry.bot.name in collisions) connections.firstOrNull { it.id == entry.bot.connectionId }?.label else null
    // Easy-access: pin the primary gateway's `default` bot under "Assistant" — unless the
    // rendered list already shows it first (then don't duplicate the row).
    val rendered = visible.groupBy { it.bot.sectionId }.toSortedMap(compareBy { it ?: "" }).values.flatten()
    val pinnedAssistant = assistant?.takeIf { a -> a in rendered && rendered.firstOrNull() != a }
    val listed = if (pinnedAssistant != null) visible.filterNot { it == pinnedAssistant } else visible
    val sections = listed.groupBy { it.bot.sectionId }.toSortedMap(compareBy { it ?: "" })

    Scaffold(
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
                val active = roster.filter { it.activeNow }
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
                            active.forEach { entry ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable {
                                            vm.markRead(entry.bot.connectionId, entry.bot.name)
                                            onOpenChat(entry.bot.connectionId, entry.bot.name)
                                        }
                                        .padding(4.dp),
                                ) {
                                    Box {
                                        FaceAvatar(entry.bot.name, 56.dp, real = avatars[avatarKey(entry.bot)])
                                        PulsingDot(
                                            dotSize = 12.dp,
                                            borderColor = MaterialTheme.colorScheme.background,
                                            modifier = Modifier.align(Alignment.BottomEnd),
                                        )
                                    }
                                    Text(
                                        entry.bot.displayName ?: entry.bot.name,
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
                    if (pinnedAssistant != null) {
                        item(key = "assistant-header") {
                            ai.hermes.bots.ui.components.SectionHeader("Assistant")
                        }
                        item(key = "assistant-${pinnedAssistant.bot.connectionId}") {
                            BotRowItem(
                                modifier = Modifier.animateItem(),
                                entry = pinnedAssistant,
                                avatar = avatars[avatarKey(pinnedAssistant.bot)],
                                gatewayLabel = gatewayLabel(pinnedAssistant),
                                onClick = {
                                    vm.markRead(pinnedAssistant.bot.connectionId, pinnedAssistant.bot.name)
                                    onOpenChat(pinnedAssistant.bot.connectionId, pinnedAssistant.bot.name)
                                },
                                onLongClick = { sheetFor = pinnedAssistant },
                            )
                        }
                    }
                    sections.forEach { (sectionId, entries) ->
                        item(key = "header-${sectionId ?: "_"}") {
                            ai.hermes.bots.ui.components.SectionHeader(
                                sectionId?.replaceFirstChar { it.uppercase() } ?: "Bots",
                            )
                        }
                        itemsIndexed(entries, key = { _, e -> "${e.bot.connectionId}:${e.bot.name}" }) { _, entry ->
                            BotRowItem(
                                modifier = Modifier.animateItem(),
                                entry = entry,
                                avatar = avatars[avatarKey(entry.bot)],
                                gatewayLabel = gatewayLabel(entry),
                                onClick = {
                                    vm.markRead(entry.bot.connectionId, entry.bot.name)
                                    onOpenChat(entry.bot.connectionId, entry.bot.name)
                                },
                                onLongClick = { sheetFor = entry },
                            )
                        }
                    }
            }
            }
        }
    }

    sheetFor?.let { entry ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Identity header — which bot am I acting on? (A23)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    FaceAvatar(
                        entry.bot.displayName ?: entry.bot.name,
                        40.dp,
                        real = avatars[avatarKey(entry.bot)],
                    )
                    Column {
                        Text(
                            entry.bot.displayName ?: entry.bot.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            entry.bot.sectionId?.replaceFirstChar { it.uppercase() } ?: "Bots",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("Routines") },
                    leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    modifier = Modifier.clickable { sheetFor = null; onOpenRoutines(entry.bot.connectionId, entry.bot.name) },
                )
                ListItem(
                    headlineContent = { Text("Edit bot") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    modifier = Modifier.clickable { sheetFor = null; onEditBot(entry.bot.connectionId, entry.bot.name) },
                )
                ListItem(
                    headlineContent = { Text(if (entry.bot.hidden) "Unhide" else "Hide") },
                    leadingContent = { Icon(Icons.Filled.Clear, contentDescription = null) },
                    modifier = Modifier.clickable { vm.setHidden(entry.bot.connectionId, entry.bot.name, !entry.bot.hidden); sheetFor = null },
                )
                ListItem(
                    headlineContent = { Text("Move to section…") },
                    leadingContent = { Icon(Icons.Filled.List, contentDescription = null) },
                    modifier = Modifier.clickable { sectionFor = entry; sheetFor = null },
                )
            }
        }
    }

    sectionFor?.let { entry ->
        var text by remember(entry) { mutableStateOf(entry.bot.sectionId.orEmpty()) }
        AlertDialog(
            onDismissRequest = { sectionFor = null },
            title = { Text("Move to section") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Section name (leave blank for Bots)") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { vm.setSection(entry.bot.connectionId, entry.bot.name, text.trim()); sectionFor = null }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { sectionFor = null }) { Text("Cancel") } },
        )
    }
}

private fun avatarKey(bot: ai.hermes.bots.data.BotRow): String = "${bot.connectionId}:${bot.name}"

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BotRowItem(
    entry: RosterEntry,
    avatar: AvatarImage?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    gatewayLabel: String? = null,
) {
    val bot = entry.bot
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FaceAvatar(bot.name, 48.dp, real = avatar)
            if (entry.unread) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
            }
            // Presence lives ON the avatar (A3): working = pulsing orange dot; idle = nothing.
            if (entry.activeNow) {
                PulsingDot(
                    dotSize = 12.dp,
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
                // B4: quiet gateway chip when this name exists on more than one gateway.
                gatewayLabel?.let { ai.hermes.bots.ui.components.ConnectionChip(it) }
            }
            Text(
                (bot.lastPreview ?: bot.description ?: "Tap to start the conversation")
                    .replace('*', ' ').replace('`', '\''),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (entry.activeNow) {
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
