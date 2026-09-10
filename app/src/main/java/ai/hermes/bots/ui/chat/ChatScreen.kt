package ai.hermes.bots.ui.chat

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ApprovalCard
import ai.hermes.bots.data.ChatItem
import ai.hermes.bots.data.ItemKind
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.ui.components.AssistantBubbleShape
import ai.hermes.bots.ui.components.ChatComposer
import ai.hermes.bots.ui.components.UserBubbleShape
import ai.hermes.bots.ui.components.WorkingStatus
import ai.hermes.bots.ui.theme.Dimens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    connectionId: String,
    botName: String,
    onBack: () -> Unit,
    onOpenRoutines: () -> Unit = {},
    onEditBot: () -> Unit = {},
) {
    val vm: ChatViewModel = viewModel(
        key = "$connectionId:$botName",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HermesBotsApp
                ChatViewModel(app, connectionId, botName)
            }
        },
    )
    val ui by vm.ui.collectAsState()
    val avatars by vm.avatars.collectAsState()
    val itemTimes by vm.itemTimes.collectAsState()
    val presence by vm.presence.collectAsState()
    val gatewayLabel by vm.gatewayLabel.collectAsState()
    var draft by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()
    val rows = remember(ui.items, itemTimes) { Transcript.build(ui.items, itemTimes) }

    // Stick to the bottom ONLY while the reader is there. Scrolling up to reread history
    // must never be hijacked by the next streamed chunk — a jump pill offers the way back.
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.let { it.index >= info.totalItemsCount - 1 } ?: true
        }
    }
    var userScrolledUp by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                userScrolledUp = true
            }
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) userScrolledUp = false }
    LaunchedEffect(rows.size, ui.statusText) {
        if (rows.isNotEmpty() && atBottom) listState.animateScrollToItem(rows.lastIndex)
    }
    val showJump = userScrolledUp && !atBottom && rows.isNotEmpty()

    var headerMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ai.hermes.bots.ui.components.FaceAvatar(
                            name = botName,
                            size = 32.dp,
                            real = avatars["$connectionId:$botName"],
                        )
                        Column {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(botName)
                                // B4: quiet gateway chip when this name exists on other gateways.
                                gatewayLabel?.let { ai.hermes.bots.ui.components.ConnectionChip(it) }
                            }
                            val subtitle = listOfNotNull(
                                presence,
                                ai.hermes.bots.ui.util.Humanize.model(ui.botModel),
                            ).joinToString(" · ")
                            if (subtitle.isNotEmpty()) {
                                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { headerMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Routines") },
                            onClick = { headerMenu = false; onOpenRoutines() },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit bot") },
                            onClick = { headerMenu = false; onEditBot() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = Dimens.GutterChat),
        ) {
            ui.error?.let { err ->
                val last = ui.items.lastOrNull()
                val alreadyInline = last?.kind == ItemKind.ERROR && last.text == err
                if (!alreadyInline) {
                    ai.hermes.bots.ui.components.ErrorLine(raw = err, botName = botName)
                }
            }
            if (ui.loading) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Opening Bot Chat…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = if (ui.approval != null) 140.dp else 8.dp),
                    ) {
                        if (rows.isEmpty()) {
                            item(key = "empty") {
                                ai.hermes.bots.ui.components.EmptyState(
                                    title = "Say hi to $botName",
                                    body = "This conversation lives here forever — anything $botName does lands in it.",
                                    avatar = {
                                        ai.hermes.bots.ui.components.FaceAvatar(
                                            botName,
                                            72.dp,
                                            real = avatars["$connectionId:$botName"],
                                        )
                                    },
                                )
                            }
                        }
                        items(rows, key = { it.key }) { row ->
                            androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                                when (row) {
                                    is TranscriptRow.Message -> ChatItemView(row.item, botName)
                                    is TranscriptRow.TimeSeparator -> TimeSeparatorRow(row.label)
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showJump,
                        enter = fadeIn(tween(150)) + androidx.compose.animation.scaleIn(tween(150)),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 4.dp, bottom = 12.dp),
                    ) {
                        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shadowElevation = 3.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    userScrolledUp = false
                                    scope?.launch { listState.animateScrollToItem(rows.lastIndex) }
                                },
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Jump to latest",
                                modifier = Modifier.padding(8.dp).size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = ui.statusText != null,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
            ) {
                ui.statusText?.let {
                    WorkingStatus(
                        botName = botName,
                        status = it,
                        avatar = avatars["$connectionId:$botName"],
                    )
                }
            }
            var lastApproval by remember { mutableStateOf<ApprovalCard?>(null) }
            LaunchedEffect(ui.approval) { if (ui.approval != null) lastApproval = ui.approval }
            val pinnedCard = ui.approval ?: lastApproval
            val showApproval = pinnedCard != null &&
                (ui.approval != null || ui.approvalResolved != null || ui.approvalExpired)
            AnimatedVisibility(
                visible = showApproval,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
            ) {
                if (pinnedCard != null) {
                    ApprovalCardView(pinnedCard, ui.approvalResolved, ui.approvalExpired, onRespond = vm::respond)
                }
            }
            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                placeholder = if (ui.streaming) "Steer the running turn…" else "Message $botName",
                streaming = ui.streaming,
                onSend = {
                    vm.send(draft)
                    draft = ""
                },
                onSteer = {
                    vm.steer(draft.trim())
                    draft = ""
                },
                onInterrupt = { vm.interrupt() },
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ChatItemView(item: ChatItem, botName: String) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    when (item.kind) {
        ItemKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = UserBubbleShape,
            ) {
                Text(
                    item.text,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .widthIn(max = maxBubbleWidth),
                )
            }
        }
        // Assistant output never floats naked — soft container with a 4 dp sender corner (A4).
        ItemKind.ASSISTANT -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = AssistantBubbleShape,
            modifier = Modifier.padding(start = 4.dp).widthIn(min = 64.dp),
        ) {
            Row(
                Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .widthIn(max = maxBubbleWidth),
                verticalAlignment = Alignment.Bottom,
            ) {
                MarkdownText(text = item.text, modifier = Modifier.weight(1f, fill = false))
                if (item.streaming) BlinkingCaret()
            }
        }
        ItemKind.TOOL -> ToolChip(item)
        // The one inline system-line style, shared with the banner (A28).
        ItemKind.ERROR -> ai.hermes.bots.ui.components.ErrorLine(raw = item.text, botName = botName)
    }
}

@Composable
private fun TimeSeparatorRow(label: String) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Streaming caret: a blinking 2x18 dp box — no text reflow (A8). */
@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    androidx.compose.foundation.layout.Box(
        Modifier
            .padding(start = 2.dp, bottom = 3.dp)
            .size(width = 2.dp, height = 18.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
    )
}

@Composable
private fun ToolChip(item: ChatItem) {
    var open by remember(item.id) { mutableStateOf(false) }
    val expandable = item.summary != null || item.text.isNotBlank()
    // Soft entrance for newly appearing chips (A8).
    val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visibleState = entrance,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().clickable(enabled = expandable) { open = !open },
            ) {
            // Expanded detail lives INSIDE the chip surface (A14).
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    Modifier.heightIn(min = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (item.streaming) {
                        ai.hermes.bots.ui.components.PulsingDot(dotSize = 8.dp)
                        Text(
                            "Running ${item.toolName ?: "tool"}…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            item.toolName ?: "tool",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    item.durationS?.let {
                        Text(
                            "· " + String.format(Locale.US, "%.1fs", it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expandable) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (open) "▾" else "▸",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (open) {
                    item.summary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (item.text.isNotBlank()) {
                        Text(
                            item.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                            maxLines = 8,
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ApprovalCardView(card: ApprovalCard, resolved: String?, expired: Boolean, onRespond: (String) -> Unit) {
    val kindLabel = when (card.kind) {
        Catalog.EVENT_CLARIFY_REQUEST -> "Question"
        Catalog.EVENT_SUDO_REQUEST -> "Elevated access requested"
        Catalog.EVENT_SECRET_REQUEST -> "Secret requested"
        else -> "Approval needed"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Kind demoted to an overline; the ask itself is the title (A13).
            Text(kindLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(
                card.command ?: kindLabel,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (expired) TextDecoration.LineThrough else null,
                color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            when {
                expired -> Text(
                    "Expired — no action taken",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                resolved != null -> {
                    card.choices.forEachIndexed { index, choice ->
                        ChoiceRow(
                            letter = ('A' + index).toString(),
                            label = choice,
                            chosen = choice == resolved,
                            dimmed = choice != resolved,
                            enabled = false,
                            onChoose = {},
                        )
                    }
                    if (card.choices.none { it == resolved }) {
                        ChoiceRow(letter = "✓", label = resolved, chosen = true, dimmed = false, enabled = false, onChoose = {})
                    }
                }
                else -> card.choices.forEachIndexed { index, choice ->
                    ChoiceRow(
                        letter = ('A' + index).toString(),
                        label = choice,
                        chosen = false,
                        dimmed = false,
                        enabled = true,
                        onChoose = { onRespond(choice) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    letter: String,
    label: String,
    chosen: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (chosen) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled) { onChoose() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (chosen) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Chosen",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            letter,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.alpha(if (dimmed) 0.5f else 1f),
        )
    }
}
