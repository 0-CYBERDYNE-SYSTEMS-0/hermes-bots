package ai.hermes.bots.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.hermes.bots.ui.chat.ChatScreen
import ai.hermes.bots.ui.connections.ConnectionsScreen
import ai.hermes.bots.ui.groups.GroupChatScreen
import ai.hermes.bots.ui.groups.GroupsScreen
import ai.hermes.bots.ui.editor.BotEditorScreen
import ai.hermes.bots.ui.roster.RosterScreen
import ai.hermes.bots.ui.routines.RoutinesScreen
import ai.hermes.bots.ui.settings.NotificationsScreen
import ai.hermes.bots.ui.settings.SettingsScreen

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = "roster",
        enterTransition = { fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 } },
        exitTransition = { fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 24 } },
        popEnterTransition = { fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 24 } },
        popExitTransition = { fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 24 } },
    ) {
        composable("roster") {
            RosterScreen(
                onOpenChat = { connectionId, botName -> nav.navigate("chat/$connectionId/$botName") },
                onOpenGateways = { nav.navigate("connections") },
                onOpenNotifications = { nav.navigate("notifications") },
                onOpenSettings = { nav.navigate("settings") },
                onNewBot = { nav.navigate("editor") },
                onEditBot = { connectionId, botName -> nav.navigate("editor/$connectionId/$botName") },
                onOpenRoutines = { connectionId, botName -> nav.navigate("routines/$connectionId/$botName") },
                onOpenGroups = { nav.navigate("groups") },
            )
        }
        composable("notifications") {
            NotificationsScreen(onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
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
                onOpenRoutines = { nav.navigate("routines/$connectionId/$botName") },
                onEditBot = { nav.navigate("editor/$connectionId/$botName") },
            )
        }
    }
}
