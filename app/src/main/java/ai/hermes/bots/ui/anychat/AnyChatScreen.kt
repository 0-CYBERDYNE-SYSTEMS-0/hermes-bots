package ai.hermes.bots.ui.anychat

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AnyChatEntry
import ai.hermes.bots.data.AnyChatMember
import ai.hermes.bots.data.AnyChatMemberState
import ai.hermes.bots.data.AnyChatRoom
import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.BotNameCollisions
import ai.hermes.bots.data.ItemKind
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.ui.components.AssistantBubbleShape
import ai.hermes.bots.ui.components.ChatComposer
import ai.hermes.bots.ui.components.ConnectionChip
import ai.hermes.bots.ui.components.EmptyState
import ai.hermes.bots.ui.components.ErrorLine
import ai.hermes.bots.ui.components.FaceAvatar
import ai.hermes.bots.ui.components.SectionHeader
import ai.hermes.bots.ui.components.UserBubbleShape
import ai.hermes.bots.ui.theme.Dimens
import android.app.Application
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AnyChatRoomUi(
    val room: AnyChatRoom? = null,
    val entries: List<AnyChatEntry> = emptyList(),
    val streaming: Boolean = false,
    val opening: Boolean = false,
    val members: List<AnyChatMemberState> = emptyList(),
)

/**
 * AnyChat (FLEET-CONNECT-SPEC B2): client-orchestrated cross-gateway rooms. The repo owns
 * the per-member canonical sessions; this VM only projects repo state for the UI.
 */
class AnyChatViewModel(app: Application, private val roomId: String?) : AndroidViewModel(app) {
    private val graph = (app as HermesBotsApp).graph
    private val repo = graph.anyChat

    val rooms: StateFlow<List<AnyChatRoom>> = repo.rooms

    val avatars: StateFlow<Map<String, AvatarImage>> = graph.roster.avatars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Full roster across ALL connections — the member picker spans every gateway. */
    val roster: StateFlow<List<RosterEntry>> = graph.roster.roster
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** B4: bot names present on more than one connection. */
    val collisionNames: StateFlow<Set<String>> = graph.roster.roster
        .map { rows -> BotNameCollisions.compute(rows.map { it.bot }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val connectionLabels: StateFlow<Map<String, String>> = graph.connections.connections
        .map { conns -> conns.associate { it.id to it.label } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val roomUi: StateFlow<AnyChatRoomUi> = combine(
        repo.rooms,
        repo.transcripts,
        repo.streaming,
        repo.memberStates,
    ) { rooms, transcripts, streaming, memberStates ->
        val room = rooms.firstOrNull { it.id == roomId }
        val states = memberStates[roomId].orEmpty()
        AnyChatRoomUi(
            room = room,
            entries = transcripts[roomId].orEmpty(),
            streaming = !streaming[roomId].isNullOrEmpty(),
            opening = room != null && room.members.any { m ->
                states.firstOrNull { it.member.key == m.key }?.settled != true
            },
            members = states,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnyChatRoomUi())

    init {
        if (roomId != null) repo.openRoom(roomId)
    }

    fun createRoom(name: String, members: List<AnyChatMember>, onCreated: (AnyChatRoom) -> Unit) {
        if (members.size < 2) return
        onCreated(repo.createRoom(name, members))
    }

    fun deleteRoom() {
        roomId?.let { repo.deleteRoom(it) }
    }

    fun send(text: String) {
        roomId?.let { repo.send(it, text) }
    }

    fun steer(text: String) {
        roomId?.let { repo.steer(it, text) }
    }

    fun interruptAll() {
        roomId?.let { repo.interrupt(it) }
    }
}

private fun memberKeyOf(connectionId: String, botName: String) = "$connectionId:$botName"

/** Room list + create dialog — the "Any chats" entry screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnyChatScreen(
    onBack: () -> Unit,
    onOpenRoom: (roomId: String, roomName: String) -> Unit,
    vm: AnyChatViewModel = viewModel(
        key = "anychat-list",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HermesBotsApp
                AnyChatViewModel(app, roomId = null)
            }
        },
    ),
) {
    val rooms by vm.rooms.collectAsState()
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Any chats") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) { Icon(Icons.Filled.Add, contentDescription = "New any chat") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.GutterScreen)) {
            if (rooms.isEmpty()) {
                EmptyState(
                    title = "Bring any bots together",
                    body = "One chat across every gateway — you talk once, each bot answers here.",
                    avatar = {
                        Box(Modifier.size(72.dp)) {
                            FaceAvatar("hermes-mesh-a", 56.dp, modifier = Modifier.align(Alignment.CenterStart))
                            FaceAvatar("hermes-mesh-b", 56.dp, modifier = Modifier.align(Alignment.Center))
                        }
                    },
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rooms, key = { it.id }) { room ->
                    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (pressed) 0.98f else 1f,
                        label = "press-scale",
                    )
                    Card(
                        onClick = { onOpenRoom(room.id, room.name) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { scaleX = scale; scaleY = scale },
                        interactionSource = interaction,
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(room.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                room.members.joinToString(" · ") { it.botName }.ifBlank { "no members" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        AnyChatCreateDialog(
            roster = vm.roster.collectAsState().value,
            connectionLabels = vm.connectionLabels.collectAsState().value,
            collisionNames = vm.collisionNames.collectAsState().value,
            onDismiss = { creating = false },
            onCreate = { name, members ->
                creating = false
                vm.createRoom(name, members) { onOpenRoom(it.id, it.name) }
            },
        )
    }
}

/** Member picker spanning ALL connections, grouped per gateway with quiet chips. */
@Composable
private fun AnyChatCreateDialog(
    roster: List<RosterEntry>,
    connectionLabels: Map<String, String>,
    collisionNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (name: String, members: List<AnyChatMember>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }
    val byConnection = roster.filter { !it.bot.hidden }.groupBy { it.bot.connectionId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New any chat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Chat name") }, singleLine = true)
                Text(
                    "Pick two or more bots — they can live on different gateways.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (byConnection.isEmpty()) {
                    Text("No bots yet — connect a gateway first.", style = MaterialTheme.typography.bodySmall)
                }
                byConnection.forEach { (connectionId, entries) ->
                    val label = connectionLabels[connectionId] ?: "Gateway"
                    SectionHeader(label)
                    entries.forEach { entry ->
                        val key = memberKeyOf(entry.bot.connectionId, entry.bot.name)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable {
                                    selected.value = if (key in selected.value) selected.value - key else selected.value + key
                                },
                        ) {
                            Checkbox(
                                checked = key in selected.value,
                                onCheckedChange = { checked ->
                                    selected.value = if (checked) selected.value + key else selected.value - key
                                },
                            )
                            FaceAvatar(entry.bot.name, 32.dp)
                            Text(
                                entry.bot.displayName ?: entry.bot.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (entry.bot.name in collisionNames) {
                                ConnectionChip(label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val members = roster
                        .filter { memberKeyOf(it.bot.connectionId, it.bot.name) in selected.value }
                        .map { AnyChatMember(it.bot.connectionId, it.bot.name) }
                    onCreate(name, members)
                },
                enabled = name.isNotBlank() && selected.value.size >= 2,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One AnyChat room: member-tagged round log + fan-out composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnyChatRoomScreen(
    roomId: String,
    roomName: String,
    onBack: () -> Unit,
    vm: AnyChatViewModel = viewModel(
        key = "anychat:$roomId",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HermesBotsApp
                AnyChatViewModel(app, roomId = roomId)
            }
        },
    ),
) {
    val ui by vm.roomUi.collectAsState()
    val avatars by vm.avatars.collectAsState()
    var draft by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.entries.size, ui.statusTextForScroll()) {
        if (ui.entries.isNotEmpty()) listState.animateScrollToItem((ui.entries.size - 1).coerceAtLeast(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text(roomName)
                        val memberLine = ui.room?.members?.joinToString(", ") { it.botName } ?: ""
                        if (memberLine.isNotEmpty()) {
                            Text(memberLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Stop current turns") }, onClick = { vm.interruptAll(); menu = false })
                        DropdownMenuItem(
                            text = { Text("Delete this chat", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; vm.deleteRoom(); onBack() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = Dimens.GutterChat),
        ) {
            if (ui.opening && ui.entries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Opening the bots' chats…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 8.dp),
                ) {
                    if (ui.entries.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                title = "Say hello to everyone",
                                body = "Your message reaches every bot in this chat at once.",
                                avatar = {
                                    Box(Modifier.size(72.dp)) {
                                        FaceAvatar("hermes-room-a", 56.dp, modifier = Modifier.align(Alignment.CenterStart))
                                        FaceAvatar("hermes-room-b", 56.dp, modifier = Modifier.align(Alignment.Center))
                                    }
                                },
                            )
                        }
                    }
                    itemsIndexed(ui.entries, key = { _, e -> e.id }) { _, entry ->
                        Box(Modifier.animateItem()) {
                            AnyChatEntryView(entry, avatars[entry.memberKey])
                        }
                    }
                }
            }
            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                placeholder = if (ui.streaming) "Steer the running turns…" else "Message everyone",
                streaming = ui.streaming,
                onSend = {
                    vm.send(draft)
                    draft = ""
                },
                onSteer = {
                    vm.steer(draft.trim())
                    draft = ""
                },
                onInterrupt = { vm.interruptAll() },
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

private fun AnyChatRoomUi.statusTextForScroll(): Int = entries.count { it.streaming }

@Composable
private fun AnyChatEntryView(entry: AnyChatEntry, avatar: AvatarImage?) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
    when (entry.kind) {
        ItemKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = UserBubbleShape,
            ) {
                Text(
                    entry.text,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .widthIn(max = maxBubbleWidth),
                )
            }
        }
        ItemKind.ASSISTANT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FaceAvatar(entry.memberName ?: "bot", 28.dp, real = avatar)
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        entry.memberName ?: "bot",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // AnyChat always tags the owning gateway (spec B2: av + gateway chip).
                    entry.gatewayLabel?.let { ConnectionChip(it) }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = AssistantBubbleShape,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Row(
                        Modifier
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .widthIn(max = maxBubbleWidth),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        ai.hermes.bots.ui.chat.MarkdownText(
                            text = entry.text,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (entry.streaming) BlinkingCaret()
                    }
                }
            }
        }
        ItemKind.TOOL -> ToolChipRow(entry)
        ItemKind.ERROR -> ErrorLine(raw = entry.text, botName = entry.memberName ?: "bot")
    }
}

@Composable
private fun ToolChipRow(entry: AnyChatEntry) {
    var open by remember(entry.id) { mutableStateOf(false) }
    val expandable = entry.summary != null || entry.text.isNotBlank()
    Column(Modifier.fillMaxWidth().padding(start = 36.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().clickable(enabled = expandable) { open = !open },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    Modifier.heightIn(min = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (entry.streaming) {
                        ai.hermes.bots.ui.components.PulsingDot(dotSize = 8.dp)
                        Text(
                            "Running ${entry.toolName ?: "tool"}…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else {
                        Text(
                            entry.toolName ?: "tool",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.memberName?.let {
                        Text(
                            "· $it",
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
                    entry.summary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (entry.text.isNotBlank()) {
                        Text(
                            entry.text,
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

/** Streaming caret, matching the canonical chat (grok-shine A8). */
@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Box(
        Modifier
            .padding(start = 2.dp, bottom = 3.dp)
            .size(width = 2.dp, height = 18.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
    )
}
