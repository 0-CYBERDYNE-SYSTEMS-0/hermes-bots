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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory


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
    var draft by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.log.size) { if (ui.log.isNotEmpty()) listState.animateScrollToItem(ui.log.lastIndex) }

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
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = ai.hermes.bots.ui.theme.Dimens.GutterChat)) {
            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Control-plane events (turn.settled, room.activity, …) stay out of the user's
                // round log — only user/member messages render (UI-SPEC §5.8: no raw protocol names).
                val roundLog = ui.log.filter { it.kind == "message.user" || it.kind.startsWith("message.member") || it.kind == "message.agent" }
                items(roundLog, key = { it.eventId ?: it.raw.toString() }) { entry ->
                    GroupLogItem(entry)
                }
            }
            ai.hermes.bots.ui.components.ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Message the group",
                streaming = false,
                onSend = {
                    vm.send(draft)
                    draft = ""
                },
                onSteer = {},
                onInterrupt = {},
                modifier = Modifier.padding(vertical = 6.dp),
            )
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
        // Member message = 28 dp blobatar gutter + soft container (A19); name is quiet gray.
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
        // Unknown/control kinds never render raw protocol strings (A19).
    }
}
