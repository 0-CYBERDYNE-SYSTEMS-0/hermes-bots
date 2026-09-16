package ai.hermes.bots.ui.chats

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.MergedBot
import ai.hermes.bots.ui.components.FaceAvatar
import ai.hermes.bots.ui.roster.RosterViewModel
import ai.hermes.bots.ui.theme.Dimens
import ai.hermes.bots.ui.theme.HermesTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** Gallery-faithful conversation hub; every bot row retains its gateway identity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onOpenChat: (connectionId: String, botName: String) -> Unit,
    onOpenAnyChats: () -> Unit,
    onOpenGroupChats: () -> Unit,
    vm: RosterViewModel = viewModel(),
) {
    val roster by vm.merged.collectAsState()
    val connections by vm.connections.collectAsState()
    val avatars by vm.avatars.collectAsState()
    val labels = connections.associate { it.id to it.label }
    val recent = roster.rows
        .filterNot { it.hidden }
        .sortedByDescending { it.lastActiveMs ?: 0L }
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    val stackHubCards = configuration.screenWidthDp < 340 || fontScale > 1.3f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats", style = HermesTheme.typography.screenTitle) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
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
            item(key = "chat-hubs") {
                if (stackHubCards) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatHubCard(
                            title = "Any chats",
                            description = "Talk across gateways",
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = HermesTheme.colors.primary) },
                            onClick = onOpenAnyChats,
                        )
                        ChatHubCard(
                            title = "Group chats",
                            description = "Rooms on a gateway",
                            icon = { Icon(Icons.Filled.DateRange, contentDescription = null, tint = HermesTheme.colors.primary) },
                            onClick = onOpenGroupChats,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatHubCard(
                            title = "Any chats",
                            description = "Talk across gateways",
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = HermesTheme.colors.primary) },
                            onClick = onOpenAnyChats,
                            modifier = Modifier.weight(1f),
                        )
                        ChatHubCard(
                            title = "Group chats",
                            description = "Rooms on a gateway",
                            icon = { Icon(Icons.Filled.DateRange, contentDescription = null, tint = HermesTheme.colors.primary) },
                            onClick = onOpenGroupChats,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item(key = "recent-title") {
                Text(
                    text = "Recent bots",
                    style = HermesTheme.typography.sectionTitle,
                    color = HermesTheme.colors.text,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
            }
            if (recent.isEmpty()) {
                item(key = "recent-empty") {
                    Text(
                        text = "No bot conversations yet.",
                        style = HermesTheme.typography.bodySmall,
                        color = HermesTheme.colors.textMuted,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            } else {
                items(recent, key = { "${it.primary.bot.connectionId}:${it.name}" }) { bot ->
                    RecentBotRow(
                        bot = bot,
                        machineLabel = labels[bot.primary.bot.connectionId],
                        avatar = avatars["${bot.primary.bot.connectionId}:${bot.name}"],
                        onClick = { onOpenChat(bot.primary.bot.connectionId, bot.name) },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun ChatHubCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 112.dp),
        shape = RoundedCornerShape(18.dp),
        color = HermesTheme.colors.surfaceRaised,
        border = BorderStroke(1.dp, HermesTheme.colors.line),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Text(
                text = title,
                style = HermesTheme.typography.machineName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = HermesTheme.typography.bodySmall,
                color = HermesTheme.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentBotRow(
    bot: MergedBot,
    machineLabel: String?,
    avatar: AvatarImage?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FaceAvatar(bot.name, 40.dp, real = avatar)
        Column(Modifier.weight(1f)) {
            Text(
                text = bot.displayName ?: bot.name,
                style = HermesTheme.typography.botName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOfNotNull(
                        machineLabel?.takeIf { it.isNotBlank() },
                        bot.preview?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "No recent preview" },
                    style = HermesTheme.typography.bodySmall,
                    color = HermesTheme.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (bot.unread) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(HermesTheme.colors.primary, CircleShape)
                            .semantics { contentDescription = "Unread" },
                    )
                }
            }
        }
        if (bot.activeNow) {
            Text(
                text = "working",
                style = HermesTheme.typography.metadataStrong,
                color = HermesTheme.colors.success,
                maxLines = 1,
            )
        }
    }
}
