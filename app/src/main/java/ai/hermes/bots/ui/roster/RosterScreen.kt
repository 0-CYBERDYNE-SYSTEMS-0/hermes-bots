package ai.hermes.bots.ui.roster

import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.RosterEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterScreen(
    onOpenChat: (connectionId: String, botName: String) -> Unit,
    onOpenGateways: () -> Unit,
    vm: RosterViewModel = viewModel(),
) {
    val roster by vm.roster.collectAsState()
    val avatars by vm.avatars.collectAsState()
    var search by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }

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
    val sections = visible.groupBy { it.bot.sectionId }.toSortedMap(compareBy { it ?: "" })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bots") },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) search = "" }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenGateways) {
                        Icon(Icons.Filled.Settings, contentDescription = "Gateways")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (searchOpen) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search bots") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val active = roster.filter { it.activeNow }
                if (active.isNotEmpty()) {
                    Text("Active now", style = MaterialTheme.typography.labelMedium)
                    active.forEach { entry ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(2.dp),
                        ) {
                            BotAvatar(entry.bot.name, avatars[avatarKey(entry.bot)], size = 28.dp)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (hiddenCount > 0) {
                    FilterChip(
                        selected = showHidden,
                        onClick = { showHidden = !showHidden },
                        label = { Text("Hidden ($hiddenCount)") },
                    )
                }
            }
            if (visible.isEmpty()) {
                Text(
                    if (showHidden) "No hidden bots" else "No bots yet — add or check Gateways",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sections.forEach { (sectionId, entries) ->
                    item(key = "header-${sectionId ?: "_"}") {
                        Text(
                            sectionId?.replaceFirstChar { it.uppercase() } ?: "Bots",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                    itemsIndexed(entries, key = { _, e -> "${e.bot.connectionId}:${e.bot.name}" }) { _, entry ->
                        BotRowItem(
                            entry = entry,
                            avatar = avatars[avatarKey(entry.bot)],
                            onClick = {
                                vm.markRead(entry.bot.connectionId, entry.bot.name)
                                onOpenChat(entry.bot.connectionId, entry.bot.name)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun avatarKey(bot: ai.hermes.bots.data.BotRow): String = "${bot.connectionId}:${bot.name}"

@Composable
private fun BotRowItem(entry: RosterEntry, avatar: AvatarImage?, onClick: () -> Unit) {
    val bot = entry.bot
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            BotAvatar(bot.name, avatar, size = 44.dp)
            if (entry.unread) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                bot.displayName ?: bot.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                bot.lastPreview ?: bot.description ?: bot.model ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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

@Composable
private fun BotAvatar(name: String, avatar: AvatarImage?, size: Dp) {
    if (avatar != null) {
        val bitmap = remember(avatar.bytes) {
            BitmapFactory.decodeByteArray(avatar.bytes, 0, avatar.bytes.size)
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = name,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    FallbackAvatar(name, size)
}

@Composable
private fun FallbackAvatar(name: String, size: Dp) {
    val palette = listOf(
        Color(0xFF3E6B79), // deep powder blue
        Color(0xFF89CEDC), // light powder blue
        Color(0xFF8C4218), // burnt orange
        Color(0xFFFFB68C), // light burnt orange
        Color(0xFF1E4E5A), // powder blue container
    )
    val color = palette[abs(name.hashCode()) % palette.size]
    Box(
        Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
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
