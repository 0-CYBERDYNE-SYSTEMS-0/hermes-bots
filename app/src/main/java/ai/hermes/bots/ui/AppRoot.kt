package ai.hermes.bots.ui

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.FleetProvisioning
import ai.hermes.bots.data.GatewayDraft
import ai.hermes.bots.ui.anychat.AnyChatRoomScreen
import ai.hermes.bots.ui.anychat.AnyChatScreen
import ai.hermes.bots.ui.activity.ActivityScreen
import ai.hermes.bots.ui.chats.ChatsScreen
import ai.hermes.bots.ui.chat.ChatScreen
import ai.hermes.bots.ui.connections.ConnectionsScreen
import ai.hermes.bots.ui.groups.GroupChatScreen
import ai.hermes.bots.ui.groups.GroupsScreen
import ai.hermes.bots.ui.editor.BotEditorScreen
import ai.hermes.bots.ui.roster.RosterScreen
import ai.hermes.bots.ui.routines.RoutinesScreen
import ai.hermes.bots.ui.settings.FleetProvisionDialog
import ai.hermes.bots.ui.settings.NotificationsScreen
import ai.hermes.bots.ui.settings.SettingsScreen
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.launch

/** One-shot provisioning deep link delivered from MainActivity (B1a). */
data class DeepLinkLaunch(val uri: String, val seq: Long)

/** One-shot notification-tap chat target delivered from MainActivity (SV-15). */
data class PendingChatLaunch(val connectionId: String, val botName: String, val seq: Long)

@Composable
fun AppRoot(deepLink: DeepLinkLaunch? = null, pendingChat: PendingChatLaunch? = null) {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as HermesBotsApp
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentRoot = RootDestination.fromRoute(currentRoute)
    val pendingApprovals by app.graph.inbox.pending.collectAsState()
    val pulseBadgeCount = pendingApprovals.count { !it.expired }
    var provisionDraft by remember { mutableStateOf<GatewayDraft?>(null) }

    // Deep link → pre-filled add/edit gateway dialog (FLEET-CONNECT-SPEC B1a).
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        provisionDraft = app.graph.provisioning.fromDeepLink(link.uri)
    }
    // Notification tap → land directly in that bot's chat (SV-15); seq makes a repeated
    // identical pair still fire, launchSingleTop keeps a re-tap from stacking screens.
    LaunchedEffect(pendingChat) {
        val target = pendingChat ?: return@LaunchedEffect
        nav.navigate("chat/${Uri.encode(target.connectionId)}/${Uri.encode(target.botName)}") {
            launchSingleTop = true
        }
    }
    provisionDraft?.let { draft ->
        FleetProvisionDialog(
            draft = draft,
            onProbe = { url, auth ->
                app.graph.gateways.probe(
                    ConnectionRecord(
                        id = "probe",
                        label = "",
                        baseUrl = FleetProvisioning.normalizeBaseUrl(url),
                        auth = auth,
                    ),
                )
            },
            onDismiss = {
                provisionDraft = null
                app.graph.provisioning.consume()
            },
            onSave = { saved ->
                provisionDraft = null
                app.graph.provisioning.consume()
                scope.launch {
                    app.graph.provisioning.save(saved)
                    nav.navigate("connections") { launchSingleTop = true }
                }
            },
        )
    }

    Scaffold(
      bottomBar = {
        if (currentRoot != null) {
          NavigationBar {
            RootDestination.values.forEach { destination ->
              NavigationBarItem(
                selected = currentRoot == destination,
                onClick = {
                  nav.navigate(destination.route) {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                  }
                },
                icon = {
                  BadgedBox(
                    badge = {
                      if (destination == RootDestination.PULSE && pulseBadgeCount > 0) {
                        Badge { Text(pulseBadgeCount.toString()) }
                      }
                    },
                  ) {
                    Icon(
                      when (destination) {
                        RootDestination.FLEET -> Icons.Filled.List
                        RootDestination.PULSE -> Icons.Filled.Notifications
                        RootDestination.CHATS -> Icons.Filled.List
                        RootDestination.SETTINGS -> Icons.Filled.Settings
                      },
                      contentDescription = destination.title,
                    )
                  }
                },
                label = { Text(destination.title) },
              )
            }
          }
        }
      },
    ) { contentPadding ->
      NavHost(
        navController = nav,
        startDestination = RootDestination.FLEET.route,
        modifier = Modifier.padding(contentPadding),
        enterTransition = { fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 } },
        exitTransition = { fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 24 } },
        popEnterTransition = { fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 24 } },
        popExitTransition = { fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 24 } },
    ) {
        composable("fleet") {
            RosterScreen(
                onOpenChat = { connectionId, botName -> nav.navigate("chat/$connectionId/${Uri.encode(botName)}") },
                onOpenGateways = { nav.navigate("connections") },
                onOpenNotifications = { nav.navigate("pulse") },
                onOpenSettings = { nav.navigate("settings") },
                onNewBot = { nav.navigate("editor") },
                onEditBot = { connectionId, botName -> nav.navigate("editor/$connectionId/${Uri.encode(botName)}") },
                onOpenRoutines = { connectionId, botName -> nav.navigate("routines/$connectionId/${Uri.encode(botName)}") },
                onOpenGroups = { nav.navigate("groups") },
                onOpenAnyChat = { nav.navigate("anychat") },
            )
        }
        composable("pulse") {
            ActivityScreen(
                onOpenChat = { connectionId, botName -> nav.navigate("chat/${Uri.encode(connectionId)}/${Uri.encode(botName)}") },
                onOpenRoutines = { connectionId, botName -> nav.navigate("routines/$connectionId/${Uri.encode(botName)}") },
                onOpenHistory = { nav.navigate("notifications") },
                title = "Pulse",
                showUpcoming = false,
            )
        }
        composable("chats") {
            ChatsScreen(
                onOpenChat = { connectionId, botName -> nav.navigate("chat/${Uri.encode(connectionId)}/${Uri.encode(botName)}") },
                onOpenAnyChats = { nav.navigate("anychat") },
                onOpenGroupChats = { nav.navigate("groups") },
            )
        }
        // Activity console (UI-SPEC.md §4.6): the roster bell lands here; the older
        // "notifications" route stays as the full history list (§4.6 Recent → History).
        composable("activity") {
            ActivityScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { connectionId, botName ->
                    nav.navigate("chat/${Uri.encode(connectionId)}/${Uri.encode(botName)}")
                },
                onOpenRoutines = { connectionId, botName ->
                    nav.navigate("routines/$connectionId/${Uri.encode(botName)}")
                },
                onOpenHistory = { nav.navigate("notifications") },
            )
        }
        composable("notifications") {
            NotificationsScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { connectionId, botName ->
                    nav.navigate("chat/${Uri.encode(connectionId)}/${Uri.encode(botName)}")
                },
            )
        }
        composable("settings") {
            SettingsScreen(
                onOpenGateways = { nav.navigate("connections") },
            )
        }
        composable("groups") {
            GroupsScreen(
                onBack = { nav.popBackStack() },
                onOpenRoom = { connectionId, roomId, roomName -> nav.navigate("group/$connectionId/$roomId/${android.net.Uri.encode(roomName)}") },
            )
        }
        composable("group/{connectionId}/{roomId}/{roomName}") { entry ->
            GroupChatScreen(
                connectionId = entry.arguments?.getString("connectionId") ?: "",
                roomId = entry.arguments?.getString("roomId") ?: "",
                roomName = entry.arguments?.getString("roomName") ?: "",
                onBack = { nav.popBackStack() },
            )
        }
        composable("anychat") {
            AnyChatScreen(
                onBack = { nav.popBackStack() },
                onOpenRoom = { roomId, roomName ->
                    nav.navigate("anychat/$roomId/${android.net.Uri.encode(roomName)}")
                },
            )
        }
        composable("anychat/{roomId}/{roomName}") { entry ->
            AnyChatRoomScreen(
                roomId = entry.arguments?.getString("roomId") ?: "",
                roomName = entry.arguments?.getString("roomName") ?: "",
                onBack = { nav.popBackStack() },
            )
        }
        composable("connections") {
            ConnectionsScreen(onBack = { nav.popBackStack() })
        }
        composable("editor") {
            BotEditorScreen(editConnectionId = null, editName = null, onBack = { nav.popBackStack() })
        }
        composable("editor/{connectionId}/{botName}") { entry ->
            BotEditorScreen(
                editConnectionId = entry.arguments?.getString("connectionId"),
                editName = entry.arguments?.getString("botName"),
                onBack = { nav.popBackStack() },
            )
        }
        composable("routines/{connectionId}/{botName}") { entry ->
            RoutinesScreen(
                connectionId = entry.arguments?.getString("connectionId") ?: "",
                botName = entry.arguments?.getString("botName") ?: "",
                onBack = { nav.popBackStack() },
            )
        }
        composable("chat/{connectionId}/{botName}") { entry ->
            val connectionId = entry.arguments?.getString("connectionId") ?: ""
            val botName = entry.arguments?.getString("botName") ?: ""
            ChatScreen(
                connectionId = connectionId,
                botName = botName,
                onBack = { nav.popBackStack() },
                onOpenRoutines = { nav.navigate("routines/$connectionId/${Uri.encode(botName)}") },
                onEditBot = { nav.navigate("editor/$connectionId/${Uri.encode(botName)}") },
            )
      }
    }
}
}
