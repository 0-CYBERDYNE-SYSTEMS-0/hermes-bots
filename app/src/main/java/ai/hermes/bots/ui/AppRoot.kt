package ai.hermes.bots.ui

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

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "roster") {
        composable("roster") {
            RosterScreen(
                onOpenChat = { connectionId, botName -> nav.navigate("chat/$connectionId/$botName") },
                onOpenGateways = { nav.navigate("connections") },
                onNewBot = { nav.navigate("editor") },
                onEditBot = { connectionId, botName -> nav.navigate("editor/$connectionId/$botName") },
                onOpenRoutines = { connectionId, botName -> nav.navigate("routines/$connectionId/$botName") },
                onOpenGroups = { nav.navigate("groups") },
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
            )
        }
    }
}
