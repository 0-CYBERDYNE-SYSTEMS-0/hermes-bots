package ai.hermes.bots.ui.chat

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ApprovalCard
import ai.hermes.bots.data.ApprovalPinPolicy
import ai.hermes.bots.data.ChatItem
import ai.hermes.bots.data.ChatStream
import ai.hermes.bots.data.DiffLineKind
import ai.hermes.bots.data.DiffText
import ai.hermes.bots.data.ItemKind
import ai.hermes.bots.data.TodoItem
import ai.hermes.bots.data.TodoStatus
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.ui.components.AssistantBubbleShape
import ai.hermes.bots.ui.components.ChatComposer
import ai.hermes.bots.ui.components.UserBubbleShape
import ai.hermes.bots.ui.components.WorkingStatus
import ai.hermes.bots.ui.theme.BotAccent
import ai.hermes.bots.ui.theme.Dimens
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    val canSend by vm.canSend.collectAsState()
    val avatars by vm.avatars.collectAsState()
    val itemTimes by vm.itemTimes.collectAsState()
    val presence by vm.presence.collectAsState()
    val gatewayLabel by vm.gatewayLabel.collectAsState()
    val bubbleMode by vm.bubbleMode.collectAsState()
    var draft by rememberSaveable(connectionId, botName) { mutableStateOf("") }
    var composerHeightPx by remember(connectionId, botName) { mutableStateOf(0) }
    var errorBannerHeightPx by remember(connectionId, botName) { mutableStateOf(0) }
    var todoHeightPx by remember(connectionId, botName) { mutableStateOf(0) }
    var workingStatusHeightPx by remember(connectionId, botName) { mutableStateOf(0) }
    var showCommandHelp by rememberSaveable { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachImageFromUri(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachFileFromUri(uri)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Blank assistant items (message.start anchors that never received text, history
    // assistant turns that only carried tool calls) and fully blank tool items render as
    // invisible pills — desktop shows nothing for them, so neither do we. Bubble Mode drops
    // even streaming-blank bot bubbles: the typing bubble stands in while nothing is visible.
    val visibleItems = remember(ui.items, bubbleMode) { ui.items.filter { if (bubbleMode) it.bubbleVisible else it.docVisible } }
    val rows = remember(visibleItems, itemTimes) { Transcript.build(visibleItems, itemTimes) }
    val bubbleRows = remember(visibleItems, itemTimes) { buildBubbleListRows(visibleItems, itemTimes) }
    // While the turn is streaming with no visible text yet, a typing bubble stands in.
    val showTyping = bubbleMode && ui.streaming && !streamingTextVisible(visibleItems)
    val lastIndex = if (bubbleMode) {
        (bubbleRows.size - 1 + if (showTyping) 1 else 0).coerceAtLeast(0)
    } else {
        rows.lastIndex
    }

    // Stick to the bottom ONLY while the reader is there. Scrolling up to reread history
    // must never be hijacked by the next streamed chunk — a jump pill offers the way back.
    //
    // `wasAtBottom` records the reader's position before content grows. Reading live layout
    // info here would measure after rows were appended and could mistake a reader at the bottom
    // for one who had scrolled away.
    //
    // "At the end" means the viewport is scrolled to max or the last row's BOTTOM
    // edge is on screen — full visibility must NOT be required, because a row taller than
    // the viewport is never fully visible, yet docking its bottom still counts as at-bottom.
    // The only thing that can flip the flag false is the scroll position moving toward the
    // transcript top; pure appends and downward travel (incl. the programmatic re-anchor)
    // keep the previous value.
    var wasAtBottom by remember { mutableStateOf(true) }
    var prevScrollPos by remember { mutableStateOf<ScrollPos?>(null) }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            ScrollPos(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { snap ->
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val atEndNow = (last != null && last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + DockSlackPx) ||
                !listState.canScrollForward
            val prev = prevScrollPos
            val scrolledUp = prev != null && snap.isBefore(prev)
            wasAtBottom = when {
                atEndNow -> true
                scrolledUp -> false
                else -> wasAtBottom
            }
            prevScrollPos = snap
        }
    }
    var userScrolledUp by remember { mutableStateOf(false) }
    // First populated transcript (open/reload) lands on the LATEST row — a long restored
    // history must never open showing its top. After that, autoscroll stays reader-gated.
    var jumpedToLatest by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                userScrolledUp = true
            }
        }
    }
    LaunchedEffect(wasAtBottom) { if (wasAtBottom) userScrolledUp = false }
    // Keep content-growth scrolling separate from status updates. Status changes re-pin
    // immediately and once after the strip's animation settles; scrolled-up readers stay put.
    LaunchedEffect(rows.size, bubbleRows.size, showTyping) {
        if (rows.isEmpty() && !showTyping) return@LaunchedEffect
        if (!jumpedToLatest) {
            jumpedToLatest = true
            userScrolledUp = false
            listState.dockToLatest(lastIndex, animated = false)
        } else if (wasAtBottom) {
            listState.dockToLatest(lastIndex, animated = true)
        }
    }
    LaunchedEffect(ui.statusText) {
        if (rows.isEmpty() && !showTyping) return@LaunchedEffect
        if (!jumpedToLatest || !wasAtBottom) return@LaunchedEffect
        listState.dockToLatest(lastIndex, animated = false)
        delay(240) // strip expand/collapse runs 200 ms; re-check once it has settled
        if (!wasAtBottom) return@LaunchedEffect
        listState.dockToLatest(lastIndex, animated = false)
    }
    val showJump = userScrolledUp && !wasAtBottom && (rows.isNotEmpty() || showTyping)

    var headerMenu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val tick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
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
                                // Show the gateway when this name exists on other connections.
                                gatewayLabel?.let { ai.hermes.bots.ui.components.ConnectionChip(it) }
                            }
                            val running = when {
                                !ui.botProvider.isNullOrBlank() && !ui.botModel.isNullOrBlank() ->
                                    "${ui.botProvider} / ${ui.botModel}"
                                else -> "model not confirmed"
                            }
                            val subtitle = listOfNotNull(presence, running).joinToString(" · ")
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
                            text = { Text("Bubble mode") },
                            onClick = {
                                headerMenu = false
                                vm.toggleBubbleMode()
                            },
                            trailingIcon = { Checkbox(checked = bubbleMode, onCheckedChange = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Slash commands") },
                            onClick = { headerMenu = false; showCommandHelp = true },
                        )
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
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = Dimens.GutterChat),
        ) {
            val last = ui.items.lastOrNull()
            val showErrorBanner = ui.error != null && !(last?.kind == ItemKind.ERROR && last.text == ui.error)
            val density = LocalDensity.current
            val composerHeight = with(density) { composerHeightPx.toDp() }
                .takeIf { it > 0.dp } ?: 96.dp
            val errorBannerHeight = with(density) {
                if (showErrorBanner) errorBannerHeightPx.toDp() else 0.dp
            }
            val workingStatusHeight = with(density) {
                if (ui.streaming && ui.statusText != null) workingStatusHeightPx.toDp() else 0.dp
            }
            val todoMaxHeight = (maxHeight - composerHeight - errorBannerHeight - workingStatusHeight)
                .coerceAtLeast(0.dp)
                .coerceAtMost(180.dp)
            val aboveTransientHeight = errorBannerHeight +
                with(density) {
                    if (ui.todo.isNotEmpty()) todoHeightPx.toDp() else 0.dp
                } + workingStatusHeight
            val transientMaxHeight = (maxHeight - composerHeight - aboveTransientHeight)
                .coerceAtLeast(0.dp)
                .coerceAtMost(220.dp)
            Column(Modifier.fillMaxSize()) {
                if (showErrorBanner) {
                    // Keep connection and gateway errors dismissible without requiring a send.
                    Box(
                        Modifier.fillMaxWidth().onSizeChanged { errorBannerHeightPx = it.height },
                    ) {
                        ai.hermes.bots.ui.components.ErrorLine(
                            raw = ui.error.orEmpty(),
                            botName = botName,
                            onDismiss = { vm.dismissError() },
                        )
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
                            val displayCount = if (bubbleMode) bubbleRows.size else rows.size
                            items(count = displayCount, key = { idx ->
                                if (bubbleMode) bubbleRows[idx].key else rows[idx].key
                            }) { idx ->
                                androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                                    if (bubbleMode) {
                                        when (val row = bubbleRows[idx]) {
                                            is BubbleListRow.TimeSeparator -> TimeSeparatorRow(row.label)
                                            is BubbleListRow.Group -> when (val bubble = row.row) {
                                                is BubbleRow.User -> BubbleUserView(bubble.item)
                                                is BubbleRow.Bot -> BubbleBotView(bubble.item, botName, vm::openFileLink)
                                                is BubbleRow.Work -> BubbleWorkGroup(bubble.items)
                                                is BubbleRow.Always -> BubbleAlwaysView(
                                                    bubble.item,
                                                    botName,
                                                    onDismissItem = vm::dismissItem,
                                                    onInterruptStall = vm::interrupt,
                                                    onFileLink = vm::openFileLink,
                                                )
                                            }
                                        }
                                    } else {
                                        when (val row = rows[idx]) {
                                            is TranscriptRow.Message -> ChatItemView(
                                                row.item,
                                                botName,
                                                onDismissItem = vm::dismissItem,
                                                onInterruptStall = vm::interrupt,
                                                onFileLink = vm::openFileLink,
                                            )
                                            is TranscriptRow.TimeSeparator -> TimeSeparatorRow(row.label)
                                        }
                                    }
                                }
                            }
                            if (bubbleMode && showTyping) {
                                item(key = "typing") { TypingBubble() }
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
                                        scope?.launch { listState.animateScrollToItem(lastIndex) }
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
                // The plan checklist stays above the working strip until the next todo.updated
                // event and can be collapsed so it never traps the transcript.
                if (ui.todo.isNotEmpty()) {
                    Box(
                        Modifier.fillMaxWidth()
                            .onSizeChanged { todoHeightPx = it.height }
                            .heightIn(max = todoMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        TodoCard(ui.todo)
                    }
                }
                // Gated on streaming too: the VM clears statusText on
                // complete/error/stall/interrupt, and this gate guarantees the "Working —"
                // strip can never outlive a live turn even if an event arrives out of order.
                AnimatedVisibility(
                    visible = ui.streaming && ui.statusText != null,
                    modifier = Modifier.fillMaxWidth().onSizeChanged { workingStatusHeightPx = it.height },
                    enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
                ) {
                    ui.statusText?.let {
                        WorkingStatus(
                            botName = botName,
                            status = it,
                            avatar = avatars["$connectionId:$botName"],
                        )
                    }
                }
                // ApprovalPinPolicy owns the lifetime: an active card always shows; a resolved
                // card stays until the user clears it (X on the card); an expired card lingers
                // EXPIRED_LINGER_MS so the state is seen, then auto-dismisses. A fresh
                // approval resets dismissal, which is what lets it replace the pinned card.
                var lastApproval by remember { mutableStateOf<ApprovalCard?>(null) }
                var approvalDismissed by remember { mutableStateOf(false) }
                LaunchedEffect(ui.approval) {
                    if (ui.approval != null) {
                        lastApproval = ui.approval
                        approvalDismissed = false
                    }
                }
                LaunchedEffect(ui.approvalExpired) {
                    if (ui.approvalExpired) {
                        delay(ApprovalPinPolicy.EXPIRED_LINGER_MS)
                        approvalDismissed = true
                    }
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = transientMaxHeight).verticalScroll(rememberScrollState()),
                ) {
                    val pinnedCard = ui.approval ?: lastApproval
                    val showApproval = ApprovalPinPolicy.visible(
                        hasCard = pinnedCard != null,
                        active = ui.approval != null,
                        resolved = ui.approvalResolved,
                        expired = ui.approvalExpired,
                        dismissed = approvalDismissed,
                    )
                    AnimatedVisibility(
                        visible = showApproval,
                        enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
                    ) {
                        if (pinnedCard != null) {
                            ApprovalCardView(
                                pinnedCard,
                                ui.approvalResolved,
                                ui.approvalExpired,
                                onRespond = { vm.respond(it) },
                                onRespondBatch = { answers -> vm.respond("", answers) },
                                onSkip = if (
                                    canSend &&
                                    ui.approval != null &&
                                    ui.approval?.kind == Catalog.EVENT_CLARIFY_REQUEST &&
                                    ui.approval?.choices?.isEmpty() == true &&
                                    ui.approvalResolved == null &&
                                    !ui.approvalExpired
                                ) {
                                    vm::skipClarify
                                } else {
                                    null
                                },
                                // X once the card is no longer the live question — or when the
                                // The session is down and the card cannot be acted on.
                                onDismiss = if (ui.approval == null || !canSend) ({ approvalDismissed = true }) else null,
                            )
                        }
                    }
                    if (ui.runLines.isNotEmpty()) {
                        Text("Run", style = MaterialTheme.typography.labelMedium)
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ui.runLines.takeLast(8).forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    ui.commandRunning?.let { command ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Running $command on this bot's gateway…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ui.commandFeedback?.let { feedback ->
                        CommandFeedbackCard(
                            text = feedback,
                            failed = ui.commandFeedbackIsError,
                            onDismiss = vm::dismissCommandFeedback,
                        )
                    }
                    if (ui.sending) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Sending to gateway…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (canSend) {
                        TextButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            enabled = ui.commandRunning == null && !ui.sending,
                        ) { Text("Attach document") }
                        ui.pendingFile?.let { file ->
                            TextButton(onClick = vm::clearPendingFile, enabled = !ui.sending) {
                                Text("${file.filename}  ×")
                            }
                        }
                    }
                }
                if (canSend) {
                    ChatComposer(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = when {
                            ui.streaming -> "Steer the running turn…"
                            // A choice-less Question parks the turn; the composer is its answer field.
                            ui.approval?.kind == Catalog.EVENT_CLARIFY_REQUEST && ui.approval?.choices?.isEmpty() == true ->
                                "Type your answer…"
                            else -> "Message $botName"
                        },
                        streaming = ui.streaming,
                        commandRunning = ui.commandRunning != null || ui.sending,
                        pendingImage = ui.pendingImage?.filename,
                        pendingFile = ui.pendingFile?.filename,
                        onAttachImage = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRemoveImage = { vm.clearPendingImage() },
                        onSend = {
                            tick()
                            userScrolledUp = false // the sender wants eyes on the new round
                            // Belt-and-braces: during a running turn the trailing button is
                            // stop/steer only, and prompt.submit on a busy session would just
                            // draw RPC 4091 — never send here.
                            if (!ui.streaming && !ui.sending) {
                                vm.send(
                                    draft,
                                    onAccepted = { draft = "" },
                                    onPrefill = { draft = it },
                                )
                            }
                        },
                        onSteer = {
                            tick()
                            userScrolledUp = false
                            vm.steer(draft.trim())
                            draft = ""
                        },
                        onInterrupt = {
                            tick()
                            vm.interrupt()
                        },
                        modifier = Modifier.onSizeChanged {
                            if (composerHeightPx != it.height) composerHeightPx = it.height
                        }.padding(vertical = 6.dp),
                    )
                } else if (!ui.loading && !ui.modelReady && ui.error == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            if (ui.modelSwitchDeferred) "Waiting for current turn…" else "Checking live model…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                } else if (!ui.loading) {
                    // SV-01: the session never opened — typing here would go nowhere, so the
                    // composer is replaced by a quiet hint and a way back in.
                    Column(
                        Modifier.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth().alpha(0.6f),
                        ) {
                            Text(
                                "Waiting for the gateway…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        }
                        TextButton(onClick = { vm.retry() }) { Text("Try again") }
                    }
                }
            }
        }
    }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    if (showCommandHelp) {
        AlertDialog(
            onDismissRequest = { showCommandHelp = false },
            title = { Text("Slash commands") },
            text = {
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Commands run on the gateway that owns this bot.")
                    Text("/compress [topic]  — compact this conversation, optionally focused on a topic.")
                    Text("/compact [topic]  — alias for /compress.")
                    Text("Add --preview, --dry-run, or --dryrun to preview compression without applying it.")
                    Text("/new  — compress the canonical bot chat to start a fresh context; the bot chat stays the same.")
                    Text("Other installed gateway commands are forwarded when supported.")
                    Text(
                        if (draft.isBlank()) "Your current draft is empty; you can insert /compress."
                        else "Your current draft will be kept. Copy /compress, then paste it when you're ready.",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (draft.isBlank()) {
                            draft = "/compress "
                        } else {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString("/compress "))
                        }
                        showCommandHelp = false
                    },
                ) { Text(if (draft.isBlank()) "Insert /compress" else "Copy /compress") }
            },
            dismissButton = {
                TextButton(onClick = { showCommandHelp = false }) { Text("Close") }
            },
        )
    }
    ui.modelSwitchConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Switch to saved model?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${confirmation.provider} / ${confirmation.model}")
                    Text(confirmation.message)
                    confirmation.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmModelSwitch, enabled = !confirmation.switching) {
                    Text(if (confirmation.switching) "Switching…" else "Confirm and switch")
                }
            },
            dismissButton = {
                if (confirmation.canKeepCurrent) {
                    TextButton(onClick = vm::keepCurrentModel, enabled = !confirmation.switching) {
                        Text("Keep current model")
                    }
                }
            },
        )
    }
}

@Composable
private fun CommandFeedbackCard(text: String, failed: Boolean, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (failed) "Command needs attention" else "Command result",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss command result")
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()),
            ) {
                MarkdownText(text = text)
            }
        }
    }
}

@Composable
private fun ChatItemView(
    item: ChatItem,
    botName: String,
    onDismissItem: ((String) -> Unit)? = null,
    onInterruptStall: (() -> Unit)? = null,
    onFileLink: (String) -> Boolean = { false },
) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    when (item.kind) {
        ItemKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = UserBubbleShape,
                modifier = Modifier.clip(UserBubbleShape).copyOnLongPress(item.text),
            ) {
                Text(
                    item.text,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .widthIn(max = maxBubbleWidth),
                )
            }
        }
        // Keep assistant output in a soft container with a 4 dp sender corner.
        ItemKind.ASSISTANT -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = AssistantBubbleShape,
            modifier = Modifier.padding(start = 4.dp).widthIn(min = 64.dp),
        ) {
            Row(
                Modifier
                    .clip(AssistantBubbleShape)
                    .copyOnLongPress(item.text)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .widthIn(max = maxBubbleWidth),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    MarkdownText(text = item.text, onFileLink = onFileLink)
                    item.imageDataUrl?.let { TranscriptImage(it) }
                }
                if (item.streaming) BlinkingCaret()
            }
        }
        ItemKind.TOOL -> ToolChip(item)
        // The one inline system-line style, shared with the banner. Id prefixes carry
        // the client-side affordances (ChatStream KDoc): "stall-"/"quiet-" watchdog lines
        // gain an Interrupt action, "warn-" lines render server warning text verbatim —
        // every line is dismissible from the screen.
        ItemKind.ERROR -> {
            val stall = item.id.startsWith(ChatStream.STALL_ID_PREFIX)
            val quiet = item.id.startsWith(ChatStream.QUIET_ID_PREFIX)
            val warn = item.id.startsWith(ChatStream.WARNING_ID_PREFIX)
            ai.hermes.bots.ui.components.ErrorLine(
                raw = item.text,
                botName = botName,
                verbatim = stall || quiet || warn,
                onDismiss = onDismissItem?.let { onDismiss -> { onDismiss(item.id) } },
                actionLabel = if (stall || quiet) "Interrupt" else null,
                onAction = if (stall || quiet) onInterruptStall else null,
            )
        }
    }
}

/** Long-press copies the raw text — chat-app muscle memory; the haptic is the feedback. */
@Composable
private fun Modifier.copyOnLongPress(text: String): Modifier {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    return pointerInput(text) {
        detectTapGestures(onLongPress = {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        })
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

/** Blinking streaming caret that does not change text layout. */
@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    // Read alpha inside the graphicsLayer lambda (draw phase only) — the
    // old composition read rebuilt the brush every frame.
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    androidx.compose.foundation.layout.Box(
        Modifier
            .padding(start = 2.dp, bottom = 3.dp)
            .size(width = 2.dp, height = 18.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.colorScheme.onSurface),
    )
}

@Composable
private fun ToolChip(item: ChatItem) {
    var open by remember(item.id) { mutableStateOf(false) }
    // "Show all" expands the summary and output inside the chip.
    var showAll by remember(item.id) { mutableStateOf(false) }
    val detailsScrollState = rememberScrollState()
    // A diff alone is enough to make the tool chip expandable.
    val expandable = item.summary != null || item.text.isNotBlank() ||
        item.outputText != null || item.inlineDiff != null
    // Show a readable label on the chip; the raw name stays in the expandable detail.
    val toolLabel = ai.hermes.bots.ui.util.Humanize.toolLabel(item.toolName)
    // Animate newly appearing chips.
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
            // Keep expanded details inside the chip surface.
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    Modifier.heightIn(min = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (item.streaming) {
                        ai.hermes.bots.ui.components.PulsingDot(dotSize = 8.dp)
                        Text(
                            "Running $toolLabel…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else {
                        // Failed runs show an error-tinted icon instead of the success check.
                        Icon(
                            if (item.failed) Icons.Outlined.Warning else Icons.Outlined.CheckCircle,
                            contentDescription = if (item.failed) "Failed" else null,
                            modifier = Modifier.size(16.dp),
                            tint = if (item.failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        Text(
                            toolLabel,
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
                    // Show a compact change count while collapsed; render rows when expanded.
                    item.inlineDiff?.let { raw ->
                        val (added, removed) = remember(item.id, raw) { DiffText.addedRemoved(raw) }
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                                    append("+$added")
                                }
                                append("  ")
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                                    append("−$removed")
                                }
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
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
                    // Keep the raw tool name readable in the detail.
                    item.toolName?.takeIf {
                        it.isNotBlank() && !it.equals(toolLabel, ignoreCase = true)
                    }?.let {
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().then(
                            if (showAll) {
                                Modifier.heightIn(max = 240.dp).verticalScroll(detailsScrollState)
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        // Clamp the preview; expanded details remain fully scrollable.
                        item.summary?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                                maxLines = if (showAll) Int.MAX_VALUE else 6,
                                overflow = if (showAll) TextOverflow.Clip else TextOverflow.Ellipsis,
                            )
                        }
                        if (item.text.isNotBlank()) {
                            Text(
                                item.text,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                                maxLines = if (showAll) Int.MAX_VALUE else 8,
                                overflow = if (showAll) TextOverflow.Clip else TextOverflow.Ellipsis,
                            )
                        }
                        // Show flattened result output when the session omits verbose details.
                        item.outputText?.let {
                            Text(
                                it,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                                maxLines = if (showAll) Int.MAX_VALUE else 8,
                                overflow = if (showAll) TextOverflow.Clip else TextOverflow.Ellipsis,
                            )
                        }
                        item.inlineDiff?.let { DiffView(it, showAll) }
                    }
                    if (expandable) {
                        Text(
                            if (showAll) "Show less" else "Show all",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { showAll = !showAll }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * Unified-diff rows inside the expandable tool chip. Rows share one horizontal scroll so a
 * long line stays aligned across the whole hunk; collapsed shows a short preview, the
 * chip's existing "Show all" reveals the capped full diff (DiffText.MAX_LINES).
 */
@Composable
private fun DiffView(raw: String, expandedAll: Boolean) {
    val previewLines = 8
    val result = remember(raw) { DiffText.parse(raw) }
    val colors = MaterialTheme.colorScheme
    val diffScroll = rememberScrollState()
    Column(Modifier.padding(top = 6.dp)) {
        val rows = if (expandedAll) result.lines else result.lines.take(previewLines)
        rows.forEach { line ->
            val (color, bg) = when (line.kind) {
                DiffLineKind.ADD -> colors.tertiary to colors.tertiaryContainer.copy(alpha = 0.32f)
                DiffLineKind.DEL -> colors.error to colors.errorContainer.copy(alpha = 0.32f)
                DiffLineKind.HUNK -> colors.primary to Color.Transparent
                DiffLineKind.FILE, DiffLineKind.META -> colors.onSurfaceVariant to Color.Transparent
                DiffLineKind.CONTEXT -> colors.onSurface to Color.Transparent
            }
            Text(
                line.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .horizontalScroll(diffScroll)
                    .padding(horizontal = 4.dp),
            )
        }
        if (result.truncated) {
            Text(
                "…diff truncated",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The pinned plan checklist fed by todo.updated events. Header carries the done/total count;
 * per-item glyphs show completed, active, and pending states:
 * pulsing dot (active), hollow circle (pending).
 */
@Composable
private fun TodoCard(items: List<TodoItem>) {
    // Saveable: collapse survives rotation like every other screen-level UI state.
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val done = items.count { it.status == TodoStatus.DONE }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { collapsed = !collapsed },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Plan",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "  $done/${items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (collapsed) "Expand" else "Collapse",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!collapsed) {
                items.forEach { todo ->
                    Row(
                        Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (todo.status) {
                            TodoStatus.DONE -> Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = "Done",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            TodoStatus.ACTIVE -> ai.hermes.bots.ui.components.PulsingDot(dotSize = 8.dp)
                            TodoStatus.PENDING -> Box(
                                Modifier
                                    .size(8.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                            )
                        }
                        Text(
                            todo.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (todo.status == TodoStatus.DONE) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCardView(
    card: ApprovalCard,
    resolved: String?,
    expired: Boolean,
    onRespond: (String) -> Unit,
    onRespondBatch: (Map<String, String>) -> Unit,
    onSkip: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    var answers by remember(card.requestId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var secret by remember(card.requestId) { mutableStateOf("") }
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
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Render the kind as an overline and the request itself as the title.
                Text(
                    kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                if (onDismiss != null) {
                    // The icon is visual only; the full 48 dp area is the touch target.
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Text(
                card.command ?: kindLabel,
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (expired) TextDecoration.LineThrough else null,
                color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (card.questions.isNotEmpty() && resolved == null && !expired) {
                card.questions.forEach { (qid, question) ->
                    OutlinedTextField(
                        value = answers[qid].orEmpty(),
                        onValueChange = { answers = answers + (qid to it) },
                        label = { Text(question) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                }
                TextButton(
                    onClick = { onRespondBatch(answers) },
                    enabled = card.questions.all { answers[it.first]?.isNotBlank() == true },
                ) { Text("Answer questions") }
            }
            if (card.kind == Catalog.EVENT_SECRET_REQUEST && resolved == null && !expired) {
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onRespond(secret); secret = "" }, enabled = secret.isNotEmpty()) {
                    Text("Submit secret")
                }
            }
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
                card.choices.isEmpty() && card.questions.isEmpty() && card.kind != Catalog.EVENT_SECRET_REQUEST && !expired -> {
                    // A free-text clarify arrives without choices, so do not fabricate buttons.
                    // The composer is the answer field; Skip also keeps this card escapable
                    // when the session is unavailable.
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "No preset options — reply below to answer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onSkip != null) {
                            TextButton(
                                onClick = onSkip,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Skip this question") }
                        }
                    }
                }
                card.questions.isNotEmpty() || card.kind == Catalog.EVENT_SECRET_REQUEST -> Unit
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
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
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
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onChoose()
            }
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

// ---------- Bubble Mode ----------

/** Scroll-position snapshot used to detect whether the reader moved. */
private data class ScrollPos(val firstVisibleIndex: Int, val firstVisibleOffset: Int) {
    /** True when this scroll position sits earlier in the transcript than [other] (scrolled up). */
    fun isBefore(other: ScrollPos): Boolean =
        firstVisibleIndex < other.firstVisibleIndex ||
            (firstVisibleIndex == other.firstVisibleIndex && firstVisibleOffset < other.firstVisibleOffset)
}

/** Tolerance for "last row's bottom edge is docked at the viewport bottom" (px). */
private const val DockSlackPx = 8

/**
 * Scroll to [index], then consume any remaining scrollable distance so the row's BOTTOM edge
 * docks at the viewport bottom. `scrollToItem`/`animateScrollToItem` align the item's TOP —
 * for a last row taller than the viewport that leaves its tail cut off, so after
 * the jump we wait for the post-jump measure and scroll the leftover distance (clamped by
 * the scroll range, so over-estimating is harmless).
 */
private suspend fun LazyListState.dockToLatest(index: Int, animated: Boolean) {
    if (animated) animateScrollToItem(index) else scrollToItem(index)
    val info = androidx.compose.runtime.snapshotFlow { layoutInfo }
        .first { it.visibleItemsInfo.lastOrNull()?.index == it.totalItemsCount - 1 }
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    val over = last.offset + last.size - info.viewportEndOffset
    if (over > 0) {
        if (animated) animateScrollBy(over.toFloat()) else scrollBy(over.toFloat())
    }
}

/** One LazyColumn row in Bubble Mode: a grouped bubble row or a time separator. */
private sealed interface BubbleListRow {
    val key: String

    data class Group(val row: BubbleRow) : BubbleListRow {
        override val key: String = when (row) {
            is BubbleRow.Work -> "work-" + row.items.first().id
            is BubbleRow.User -> row.item.id
            is BubbleRow.Bot -> row.item.id
            is BubbleRow.Always -> row.item.id
        }
    }

    data class TimeSeparator(val atMs: Long, val label: String) : BubbleListRow {
        override val key: String = "bsep-$atMs"
    }
}

/**
 * Bubble Mode rows with time separators placed exactly where Transcript would put them:
 * keyed off the first receive stamp in each group, same gap/day rules as document mode.
 */
private fun buildBubbleListRows(items: List<ChatItem>, times: Map<String, Long>): List<BubbleListRow> {
    val out = mutableListOf<BubbleListRow>()
    var prevMs: Long? = null
    buildBubbleRows(items).forEach { row ->
        val at = when (row) {
            is BubbleRow.Work -> row.items.firstNotNullOfOrNull { times[it.id] }
            is BubbleRow.User -> times[row.item.id]
            is BubbleRow.Bot -> times[row.item.id]
            is BubbleRow.Always -> times[row.item.id]
        }
        if (at != null && Transcript.needsSeparator(prevMs, at)) {
            out += BubbleListRow.TimeSeparator(at, Transcript.label(at))
        }
        out += BubbleListRow.Group(row)
        if (at != null) prevMs = at
    }
    return out
}

/** True when the transcript's last item is a streaming assistant turn with visible text. */
private fun streamingTextVisible(items: List<ChatItem>): Boolean {
    val last = items.lastOrNull() ?: return false
    return last.kind == ItemKind.ASSISTANT && last.streaming && last.text.isNotBlank()
}

/**
 * Visibility gates. A TOOL item renders only when it has a name, summary or args text;
 * an assistant item renders when it has text — document mode keeps a streaming blank as the
 * caret-only "started typing" container, bubble mode replaces it with the typing bubble.
 */
private val ChatItem.docVisible: Boolean
    get() = when (kind) {
        ItemKind.TOOL -> !toolName.isNullOrBlank() || !summary.isNullOrBlank() || text.isNotBlank()
        ItemKind.ASSISTANT -> streaming || text.isNotBlank()
        else -> true
    }

private val ChatItem.bubbleVisible: Boolean
    get() = when (kind) {
        ItemKind.TOOL -> !toolName.isNullOrBlank() || !summary.isNullOrBlank() || text.isNotBlank()
        ItemKind.ASSISTANT -> text.isNotBlank()
        else -> true
    }

@Composable
private fun BubbleUserView(item: ChatItem) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = UserBubbleShape,
            modifier = Modifier.clip(UserBubbleShape).copyOnLongPress(item.text),
        ) {
            Text(
                item.text,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .widthIn(max = maxBubbleWidth),
            )
        }
    }
}

/** Bot turn: raised bubble with the per-bot accent as a 3 dp leading edge (§4.2 rev). */
@Composable
private fun BubbleBotView(item: ChatItem, botName: String, onFileLink: (String) -> Boolean) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = BotAccent.color(botName, darkTheme)
    val accentWidth = 3.dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = AssistantBubbleShape,
        shadowElevation = 1.dp,
        modifier = Modifier.padding(start = 4.dp).widthIn(min = 64.dp),
    ) {
        Row(
            Modifier
                .clip(AssistantBubbleShape)
                .drawBehind {
                    drawRect(
                        color = accent,
                        topLeft = Offset.Zero,
                        size = Size(accentWidth.toPx(), size.height),
                    )
                }
                .copyOnLongPress(item.text)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(max = maxBubbleWidth),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                MarkdownText(text = item.text, onFileLink = onFileLink)
                item.imageDataUrl?.let { TranscriptImage(it) }
            }
            if (item.streaming) BlinkingCaret()
        }
    }
}

/** Contiguous tool run: one compact chip, expanding inline to the existing tool chips. */
@Composable
private fun BubbleWorkGroup(items: List<ChatItem>) {
    var open by remember(items.first().id) { mutableStateOf(false) }
    val running = items.any { it.streaming }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { open = !open },
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (running) ai.hermes.bots.ui.components.PulsingDot(dotSize = 8.dp)
                Text(
                    "${if (open) "Hide work" else "Show work"} · ${items.size} ${if (items.size == 1) "step" else "steps"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (open) "▾" else "▸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { ToolChip(it) }
            }
        }
    }
}

/** Relay lines and errors: distinct, always visible — errors keep the shared ErrorLine. */
@Composable
private fun BubbleAlwaysView(
    item: ChatItem,
    botName: String,
    onDismissItem: ((String) -> Unit)? = null,
    onInterruptStall: (() -> Unit)? = null,
    onFileLink: (String) -> Boolean = { false },
) {
    if (item.kind == ItemKind.ERROR) {
        val stall = item.id.startsWith(ChatStream.STALL_ID_PREFIX)
        val quiet = item.id.startsWith(ChatStream.QUIET_ID_PREFIX)
        val warn = item.id.startsWith(ChatStream.WARNING_ID_PREFIX)
        ai.hermes.bots.ui.components.ErrorLine(
            raw = item.text,
            botName = botName,
            verbatim = stall || quiet || warn,
            onDismiss = onDismissItem?.let { onDismiss -> { onDismiss(item.id) } },
            actionLabel = if (stall || quiet) "Interrupt" else null,
            onAction = if (stall || quiet) onInterruptStall else null,
        )
        return
    }
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.widthIn(max = maxBubbleWidth),
    ) {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .copyOnLongPress(item.text)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "↔",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Bot relay",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                MarkdownText(text = item.text, onFileLink = onFileLink)
            }
        }
    }
}

@Composable
internal fun TranscriptImage(dataUrl: String) {
    val mime = remember(dataUrl) { dataUrl.substringAfter("data:").substringBefore(';').takeIf { it.startsWith("image/") } ?: "image/png" }
    val extension = if (mime == "image/jpeg") "jpg" else mime.substringAfter('/').takeIf { it in listOf("png", "webp", "gif") } ?: "png"
    val bytes = remember(dataUrl) {
        runCatching { android.util.Base64.decode(dataUrl.substringAfter("base64,"), android.util.Base64.DEFAULT) }.getOrNull()
    } ?: return
    val bitmap = remember(dataUrl) {
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    Image(bitmap = bitmap, contentDescription = "Generated image", modifier = Modifier.fillMaxWidth())
    TextButton(onClick = {
        runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "bot-image.$extension")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            }
            val target = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not save image")
            context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                ?: error("Could not write image")
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, target)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(share, "Share image"))
        }
    }) { Text("Save and share") }
}

/** §4.2 rev: the "…" stand-in while the turn is streaming with no visible text yet. */
@Composable
private fun TypingBubble() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = AssistantBubbleShape,
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypingDot(0)
            TypingDot(180)
            TypingDot(360)
        }
    }
}

/** One dot of the typing bubble: gentle alpha pulse, full cycle under 1 s. */
@Composable
private fun TypingDot(startOffsetMs: Int) {
    val transition = rememberInfiniteTransition(label = "typing-dot")
    // Read alpha in the draw phase (graphicsLayer), not during composition.
    val alpha = transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(startOffsetMs),
        ),
        label = "typing-dot-alpha",
    )
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(7.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.colorScheme.onSurface, CircleShape),
    )
}
