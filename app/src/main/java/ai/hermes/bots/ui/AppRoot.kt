package ai.hermes.bots.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.hermes.bots.ui.chat.ChatScreen
import ai.hermes.bots.ui.connections.ConnectionsScreen
import ai.hermes.bots.ui.roster.RosterScreen

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "roster") {
        composable("roster") {
            RosterScreen(
                onOpenChat = { connectionId, botName -> nav.navigate("chat/$connectionId/$botName") },
                onOpenGateways = { nav.navigate("connections") },
            )
        }
        composable("connections") {
            ConnectionsScreen(onBack = { nav.popBackStack() })
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
