package ai.hermes.bots.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import java.util.UUID


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    connectionId: String,
    roomId: String,
    roomName: String,
    onBack: () -> Unit,
    vm: GroupChatViewModel = viewModel(
        key = "group:$connectionId:$roomId",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ai.hermes.bots.HermesBotsApp
                GroupChatViewModel(app, connectionId, roomId)
            }
        },
    ),
) {
    val ui by vm.ui.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var pendingSendId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSendText by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDisband by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Control-plane events (turn.settled, room.activity, …) stay out of the user's
    // The round log shows only user and member messages, never raw protocol names.
    val roundLog = remember(ui.log) {
        ui.log.filter { it.kind == "message.user" || it.kind.startsWith("message.member") || it.kind == "message.agent" }
    }
    val sendDraft = GroupSendDraftState(draft, pendingSendId, pendingSendText)
    val unconfirmedSend = unconfirmedGroupSend(sendDraft, ui.sendOutcome, ui.busy)
    // Follow new rounds only while the reader is already near the bottom — never
    // yank the list while they've scrolled up to read history.
    LaunchedEffect(roundLog.size, ui.pending.size) {
        if (roundLog.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= info.totalItemsCount - 2) listState.animateScrollToItem(info.totalItemsCount - 1)
    }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(ui.disbanded) { if (ui.disbanded) onBack() }
    LaunchedEffect(ui.sendOutcome) {
        val outcome = ui.sendOutcome ?: return@LaunchedEffect
        val settled = resolveGroupSendDraft(
            GroupSendDraftState(draft, pendingSendId, pendingSendText),
            outcome,
        )
        draft = settled.text
        pendingSendId = settled.pendingEventId
        pendingSendText = settled.pendingText
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text(roomName)
                        ui.room?.let { Text(it.members.joinToString(", ").ifBlank { "room" }, style = MaterialTheme.typography.labelSmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Stop current round") }, onClick = { vm.stop(); menu = false })
                        DropdownMenuItem(text = { Text("Disband room") }, onClick = { menu = false; confirmDisband = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = ai.hermes.bots.ui.theme.Dimens.GutterChat)) {
            ui.error?.let { raw ->
                Text(
                    ai.hermes.bots.ui.util.Humanize.friendlyError(raw, roomName) ?: "Something went wrong — try again.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(4.dp),
                )
            }
            if (ui.loading) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Opening the room…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Approval/stuck-round cards live INSIDE the scroll container so several
                    // stacked cards can never push the composer (or the log) off-screen.
                    items(ui.pending, key = { "pending:${it.taskId}" }) { action ->
                        PendingActionCard(
                            action = action,
                            memberNames = ui.room?.memberNames ?: emptyMap(),
                            enabled = !ui.busy,
                            onResolve = vm::resolve,
                            onRetry = vm::retry,
                        )
                    }
                    items(roundLog, key = { it.eventId ?: it.raw.toString() }) { entry ->
                        GroupLogItem(entry)
                    }
                }
            }
            unconfirmedSend?.let { attempt ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Delivery not confirmed. Retry this message before sending an edit.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        enabled = !ui.busy,
                        onClick = {
                            if (vm.send(attempt.text, attempt.eventId)) {
                                pendingSendId = attempt.eventId
                                pendingSendText = attempt.text
                            }
                        },
                    ) { Text("Retry message") }
                }
            }
            ai.hermes.bots.ui.components.ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Message the group",
                streaming = false,
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        if (!canSubmitGroupDraft(text, unconfirmedSend)) {
                            scope.launch {
                                snackbar.showSnackbar("Retry the previous message before sending this edit.")
                            }
                        } else {
                            val submitted = unconfirmedSend?.let { it.text to it.eventId }
                                ?: (text to groupSendEventId(sendDraft, text, UUID.randomUUID().toString()))
                            if (vm.send(submitted.first, submitted.second)) {
                                pendingSendId = submitted.second
                                pendingSendText = submitted.first
                            }
                        }
                    }
                },
                onSteer = {},
                onInterrupt = {},
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }

    if (confirmDisband) {
        AlertDialog(
            onDismissRequest = { confirmDisband = false },
            title = { Text("Disband this room?") },
            text = { Text("This removes it for everyone.") },
            confirmButton = {
                TextButton(onClick = { confirmDisband = false; vm.disband() }) { Text("Disband") }
            },
            dismissButton = { TextButton(onClick = { confirmDisband = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PendingActionCard(
    action: ai.hermes.bots.data.GroupPendingAction,
    memberNames: Map<String, String>,
    enabled: Boolean,
    onResolve: (ai.hermes.bots.data.GroupPendingAction, String) -> Unit,
    onRetry: (ai.hermes.bots.data.GroupPendingAction) -> Unit,
) {
    // Resolve the member's display name from the room roster instead of
    // rendering the raw profile slug; humane fallback when the wire carries no name.
    val memberName = action.memberId?.let { memberNames[it] }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (action.kind == "approval") "Approval needed" else "Round stuck",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                action.command
                    ?: when {
                        memberName != null -> "$memberName needs your approval"
                        action.kind == "approval" -> "A member needs your approval"
                        else -> "A round needs your attention"
                    },
                style = MaterialTheme.typography.titleSmall,
            )
            when (action.kind) {
                // Wire contract (hosted_room_service.py approve_room_task): the choice sent is
                // exactly "once" or "deny" — the payload's choice set, humane button labels.
                "approval" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    action.choices.forEach { choice ->
                        TextButton(enabled = enabled, onClick = { onResolve(action, choice) }) {
                            Text(if (choice == "deny") "Deny" else "Approve")
                        }
                    }
                }
                else -> TextButton(enabled = enabled, onClick = { onRetry(action) }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun GroupLogItem(entry: ai.hermes.bots.data.GroupLogEntry) {
    val maxBubbleWidth = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.86f).dp
    when {
        entry.kind == "message.user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = ai.hermes.bots.ui.components.UserBubbleShape,
            ) {
                Text(
                    entry.text,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .widthIn(max = maxBubbleWidth),
                )
            }
        }
        // Member message uses a 28 dp blobatar gutter and soft container; name is quiet gray.
        entry.kind == "message.member" || entry.kind == "message.agent" -> {
            val actor = entry.actor ?: "bot"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ai.hermes.bots.ui.components.FaceAvatar(actor, 28.dp)
                Column {
                    Text(
                        actor,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = ai.hermes.bots.ui.components.AssistantBubbleShape,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        // Server text is prefixed "@handle: " — the label above already says who.
                        Text(
                            entry.actor?.let { entry.text.removePrefix("@$it: ") } ?: entry.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .widthIn(max = maxBubbleWidth),
                        )
                    }
                }
            }
        }
        // Unknown/control kinds never render raw protocol strings.
    }
}
