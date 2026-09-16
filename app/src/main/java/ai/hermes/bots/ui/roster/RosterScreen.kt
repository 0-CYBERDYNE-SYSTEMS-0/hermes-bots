package ai.hermes.bots.ui.roster

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.BotRow
import ai.hermes.bots.data.MergedBot
import ai.hermes.bots.protocol.SocketState
import ai.hermes.bots.ui.components.FaceAvatar
import ai.hermes.bots.ui.components.FaceState
import ai.hermes.bots.ui.theme.Dimens
import ai.hermes.bots.ui.theme.HermesTheme
import ai.hermes.bots.ui.theme.brandPalette
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
    val connections by vm.connections.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val socketStates by vm.socketStates.collectAsState()
    val expansionOverrides by vm.machineExpansionOverrides.collectAsState()
    val transientError by vm.transientError.collectAsState()
    val snackbar = remember { SnackbarHostState() }
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
    val available = fleet.rows
        .filter { if (showHidden) it.hidden else !it.hidden }
    fun botMatches(m: MergedBot): Boolean =
        query.isEmpty() ||
            m.name.lowercase(Locale.ROOT).contains(query) ||
            (m.displayName ?: "").lowercase(Locale.ROOT).contains(query) ||
            (m.primary.bot.description ?: "").lowercase(Locale.ROOT).contains(query) ||
            (m.preview ?: "").lowercase(Locale.ROOT).contains(query)
    val groups = FleetPresentation.groups(available, connections, socketStates)
        .mapNotNull { group ->
            if (query.isEmpty()) group
            else if (group.connection.label.lowercase(Locale.ROOT).contains(query)) group
            else group.copy(bots = group.bots.filter(::botMatches)).takeIf { it.bots.isNotEmpty() }
        }
    fun machineLabel(m: MergedBot): String? =
        connections.firstOrNull { it.id == m.primary.bot.connectionId }?.label

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Fleet",
                        style = HermesTheme.typography.screenTitle,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
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
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .padding(horizontal = Dimens.ScreenGutter),
                ) {
                    FleetSearchField(
                        value = search,
                        onValueChange = { search = it },
                        focusRequester = searchFocus,
                    )
                    if (hiddenCount > 0) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            FilterChip(
                                selected = showHidden,
                                onClick = { showHidden = !showHidden },
                                label = { Text("Hidden ($hiddenCount)") },
                            )
                        }
                    }
                    if (groups.isEmpty()) {
                        if (showHidden) {
                            Text(
                                if (query.isEmpty()) "No hidden bots" else "No hidden bots match “$search”",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(8.dp),
                            )
                        } else if (query.isNotEmpty()) {
                            ai.hermes.bots.ui.components.EmptyState(
                                title = "No matches",
                                body = "No bots or machines match “$search”.",
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
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = Dimens.BottomContentClearance),
                    ) {
                        items(groups, key = { it.connection.id }) { group ->
                            val persisted = expansionOverrides[group.connection.id]
                            val expanded = query.isNotEmpty() || FleetPresentation.isExpanded(group, persisted)
                            MachineCard(
                                group = group,
                                expanded = expanded,
                                avatars = avatars,
                                onToggle = {
                                    if (query.isEmpty()) {
                                        vm.setMachineExpanded(group.connection.id, !expanded)
                                    }
                                },
                                onOpenBot = { bot ->
                                    vm.markRead(bot.primary.bot.connectionId, bot.name)
                                    onOpenChat(bot.primary.bot.connectionId, bot.name)
                                },
                                onLongPressBot = { sheetFor = it },
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
                        m.name,
                        40.dp,
                        real = avatars[avatarKey(m.primary.bot)],
                        state = faceStateOf(m),
                    )
                    Column {
                        Text(
                            m.displayName ?: m.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            buildString {
                                append(m.sectionId?.replaceFirstChar { it.uppercase() } ?: "Bots")
                                machineLabel(m)?.let { append(" · $it") }
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
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
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

@Composable
private fun FleetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = HermesTheme.colors
    val shape = RoundedCornerShape(17.dp)
    Surface(
        shape = shape,
        color = colors.surfaceInput,
        border = if (focused) BorderStroke(1.dp, colors.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp, bottom = 13.dp)
            .heightIn(min = 48.dp),
    ) {
        Row(
            Modifier.padding(start = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.textDim,
            )
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .padding(vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                "Search bots or machines",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMuted,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "Clear search",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(13.dp))
            }
        }
    }
}

@Composable
private fun MachineCard(
    group: MachineGroup,
    expanded: Boolean,
    avatars: Map<String, AvatarImage>,
    onToggle: () -> Unit,
    onOpenBot: (MergedBot) -> Unit,
    onLongPressBot: (MergedBot) -> Unit,
) {
    val assistant = group.bots.filter {
        group.isPrimary && it.name.equals(DEFAULT_ASSISTANT_NAME, ignoreCase = true)
    }
    val active = group.bots.filter { it !in assistant && it.activeNow }
    val remaining = group.bots.filter { it !in assistant && it !in active }
    val sections = buildList {
        if (assistant.isNotEmpty()) add("Assistant" to assistant)
        if (active.isNotEmpty()) add("Active" to active)
        val grouped = remaining.groupBy { it.sectionId }
        grouped.filterKeys { it != null }.forEach { (sectionId, bots) ->
            add(sectionId!!.replaceFirstChar { it.uppercase() } to bots)
        }
        grouped[null]?.let { bots -> add("Bots" to bots) }
    }

    Surface(
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            MachineHeader(group = group, expanded = expanded, onToggle = onToggle)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(if (android.animation.ValueAnimator.areAnimatorsEnabled()) 240 else 0),
                ) + fadeIn(animationSpec = tween(if (android.animation.ValueAnimator.areAnimatorsEnabled()) 160 else 0)),
                exit = shrinkVertically(
                    animationSpec = tween(if (android.animation.ValueAnimator.areAnimatorsEnabled()) 240 else 0),
                ) + fadeOut(animationSpec = tween(if (android.animation.ValueAnimator.areAnimatorsEnabled()) 120 else 0)),
            ) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 9.dp)) {
                    sections.forEach { (label, bots) ->
                        FleetSectionLabel(label)
                        bots.forEach { bot ->
                            MergedBotRowItem(
                                bot = bot,
                                avatar = avatars[avatarKey(bot.primary.bot)],
                                onClick = { onOpenBot(bot) },
                                onLongClick = { onLongPressBot(bot) },
                                offline = group.state !is SocketState.Ready,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MachineHeader(
    group: MachineGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val ready = group.state is SocketState.Ready
    val success = brandPalette().success
    val stateColor = when (group.state) {
        is SocketState.Ready -> success
        SocketState.Connecting -> MaterialTheme.colorScheme.secondary
        is SocketState.Disconnected, SocketState.Idle -> HermesTheme.colors.textDim
    }
    val lastActive = group.bots.mapNotNull { it.lastActiveMs }.maxOrNull()
    val botLabel = "${group.botCount} ${if (group.botCount == 1) "bot" else "bots"}"
    val secondary = when (group.state) {
        is SocketState.Ready -> if (group.isPrimary) "Ready · primary gateway"
        else lastActive?.let { "Ready · last activity ${relativeTime(it)}" } ?: "Ready"
        SocketState.Connecting -> "Connecting…"
        is SocketState.Disconnected, SocketState.Idle ->
            lastActive?.let { "Offline · last seen ${relativeTime(it)}" } ?: "Offline"
    }
    val summaryStrong = when {
        !ready -> if (group.state is SocketState.Connecting) "connecting" else "offline"
        group.activeCount > 0 -> "${group.activeCount} working"
        else -> "all quiet"
    }
    val summaryDetail = buildList {
        add(
            when {
                ready && group.botCount > 0 -> botLabel
                ready -> "No bots"
                group.botCount > 0 -> "${group.botCount} cached ${if (group.botCount == 1) "bot" else "bots"}"
                else -> "No cached bots"
            },
        )
        if (ready && group.unreadCount > 0) add("${group.unreadCount} unread")
    }.joinToString(" · ")
    val summaryColor = when {
        !ready && group.state !is SocketState.Connecting -> MaterialTheme.colorScheme.error
        group.unreadCount > 0 -> HermesTheme.colors.primary
        group.activeCount > 0 -> success
        else -> MaterialTheme.colorScheme.onSurface
    }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(if (android.animation.ValueAnimator.areAnimatorsEnabled()) 220 else 0),
        label = "machine-chevron",
    )
    val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.3f

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onToggle)
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                contentDescription = buildString {
                    append(group.connection.label)
                    append(", ")
                    append(secondary)
                    append(", ")
                    append(summaryStrong)
                    append(", ")
                    append(summaryDetail)
                    if (group.isPrimary) append(", primary gateway")
                }
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            if (ready || group.state is SocketState.Connecting) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(stateColor.copy(alpha = 0.09f)),
                )
            }
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(stateColor),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    group.connection.label,
                    style = HermesTheme.typography.machineName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (group.isPrimary) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Primary gateway",
                        tint = HermesTheme.colors.primaryMachine,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                secondary,
                style = HermesTheme.typography.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (compact) {
                Text(
                    summaryStrong,
                    style = HermesTheme.typography.metadataStrong,
                    color = summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summaryDetail,
                    style = HermesTheme.typography.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!compact) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    summaryStrong,
                    style = HermesTheme.typography.metadataStrong,
                    color = summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summaryDetail,
                    style = HermesTheme.typography.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun FleetSectionLabel(label: String) {
    Text(
        label.uppercase(Locale.ROOT),
        style = HermesTheme.typography.eyebrow,
        color = HermesTheme.colors.textDim,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

private fun faceStateOf(bot: MergedBot): FaceState =
    if (bot.activeNow) FaceState.Working else FaceState.Idle

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MergedBotRowItem(
    bot: MergedBot,
    avatar: AvatarImage?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    chip: String? = null,
    offline: Boolean = false,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .alpha(if (offline || bot.hidden) 0.56f else 1f)
            .clip(RoundedCornerShape(13.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            FaceAvatar(
                bot.name,
                36.dp,
                real = avatar,
                state = faceStateOf(bot),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    bot.displayName ?: bot.name,
                    style = HermesTheme.typography.botName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (bot.unread) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(HermesTheme.colors.primary)
                            .semantics { contentDescription = "Unread" },
                    )
                }
                // Multi-gateway presence: primary's label + how many more host this bot.
                chip?.let { ai.hermes.bots.ui.components.ConnectionChip(it) }
            }
            Text(
                (bot.preview ?: "Tap to start the conversation")
                    .replace('*', ' ').replace('`', '\''),
                style = HermesTheme.typography.bodySmall,
                color = if (bot.unread) HermesTheme.colors.text else HermesTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Dimens.GapSm))
        Column(horizontalAlignment = Alignment.End) {
            if (bot.activeNow) {
                Text(
                    "working",
                    style = HermesTheme.typography.metadataStrong,
                    color = brandPalette().success,
                )
            }
            Text(
                bot.lastActiveMs?.let { relativeTime(it) } ?: if (bot.activeNow) "now" else "idle",
                style = HermesTheme.typography.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!bot.activeNow && bot.unread) {
                Text(
                    "unread",
                    style = HermesTheme.typography.metadataStrong,
                    color = HermesTheme.colors.primary,
                )
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
