package ai.hermes.bots.ui.chat

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ApprovalCard
import ai.hermes.bots.data.ChatItem
import ai.hermes.bots.data.ItemKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(connectionId: String, botName: String, onBack: () -> Unit) {
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
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.items.size, ui.statusText) {
        if (ui.items.isNotEmpty()) listState.animateScrollToItem(ui.items.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(botName)
                        ui.sessionTitle?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
        ) {
            ui.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(4.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(ui.items, key = { it.id }) { item -> ChatItemView(item) }
            }
            ui.statusText?.let {
                Text(
                    "• $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            ui.approval?.let { card ->
                ApprovalCardView(card = card, onRespond = vm::respond)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (ui.streaming) "Steer the running turn…" else "Message $botName")
                    },
                    maxLines = 4,
                )
                if (ui.streaming) {
                    IconButton(onClick = { vm.interrupt() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = {
                        if (draft.isNotBlank()) {
                            vm.steer(draft.trim())
                            draft = ""
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Steer")
                    }
                } else {
                    IconButton(onClick = {
                        if (draft.isNotBlank()) {
                            vm.send(draft)
                            draft = ""
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatItemView(item: ChatItem) {
    when (item.kind) {
        ItemKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    item.text,
                    modifier = Modifier.padding(10.dp).widthIn(max = 300.dp),
                )
            }
        }
        ItemKind.ASSISTANT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    item.text + if (item.streaming) " ▍" else "",
                    modifier = Modifier.padding(10.dp).widthIn(max = 300.dp),
                )
            }
        }
        ItemKind.TOOL -> Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val duration = item.durationS?.let { " · " + String.format(Locale.US, "%.1fs", it) } ?: ""
            Text(
                (if (item.streaming) "⚙ " else "✓ ") + (item.toolName ?: "tool") + duration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            item.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        ItemKind.ERROR -> Text(
            item.text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun ApprovalCardView(card: ApprovalCard, onRespond: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Approval needed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            card.command?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.choices.forEach { choice ->
                    FilledTonalButton(onClick = { onRespond(choice) }) { Text(choice) }
                }
            }
        }
    }
}
