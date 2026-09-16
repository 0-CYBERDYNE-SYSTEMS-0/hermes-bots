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
import ai.hermes.bots.ui.theme.Dimens
import ai.hermes.bots.ui.theme.HermesTheme
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
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
        containerColor = HermesTheme.colors.canvas,
        bottomBar = {
            if (currentRoot != null) {
                FleetBottomNavigation(
                    selected = currentRoot,
                    pulseBadgeCount = pulseBadgeCount,
                    onSelect = { destination ->
                        nav.navigate(destination.route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { contentPadding ->
      NavHost(
        navController = nav,
        startDestination = RootDestination.FLEET.route,
        modifier = Modifier.padding(contentPadding),
        enterTransition = {
            if (RootDestination.isRootRoute(initialState.destination.route) &&
                RootDestination.isRootRoute(targetState.destination.route)
            ) {
                fadeIn(tween(120))
            } else {
                fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 }
            }
        },
        exitTransition = {
            if (RootDestination.isRootRoute(initialState.destination.route) &&
                RootDestination.isRootRoute(targetState.destination.route)
            ) {
                fadeOut(tween(120))
            } else {
                fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 24 }
            }
        },
        popEnterTransition = {
            if (RootDestination.isRootRoute(initialState.destination.route) &&
                RootDestination.isRootRoute(targetState.destination.route)
            ) {
                fadeIn(tween(120))
            } else {
                fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 24 }
            }
        },
        popExitTransition = {
            if (RootDestination.isRootRoute(initialState.destination.route) &&
                RootDestination.isRootRoute(targetState.destination.route)
            ) {
                fadeOut(tween(120))
            } else {
                fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 24 }
            }
        },
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

@Composable
private fun FleetBottomNavigation(
    selected: RootDestination,
    pulseBadgeCount: Int,
    onSelect: (RootDestination) -> Unit,
) {
    val colors = HermesTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceNav)
            .drawBehind {
                drawRect(colors.lineQuiet, size = size.copy(height = 1.dp.toPx()))
            }
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().height(Dimens.BottomNavHeight)) {
            RootDestination.values.forEach { destination ->
                val isSelected = selected == destination
                val tint = if (isSelected) colors.primary else colors.textDim
                val badgeCount = if (destination == RootDestination.PULSE) pulseBadgeCount else 0
                val semanticsLabel = if (badgeCount > 0) {
                    "${destination.title}, $badgeCount unresolved ${if (badgeCount == 1) "item" else "items"}"
                } else {
                    destination.title
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics(mergeDescendants = true) {
                            contentDescription = semanticsLabel
                            role = Role.Tab
                            this.selected = isSelected
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(destination) },
                        )
                        .padding(top = 14.dp),
                ) {
                    Box {
                        Icon(
                            imageVector = destination.navigationIcon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(21.dp),
                        )
                        if (badgeCount == 1) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-3).dp)
                                    .size(7.dp)
                                    .background(colors.attention, RoundedCornerShape(50)),
                            )
                        } else if (badgeCount >= 2) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-6).dp)
                                    .height(16.dp)
                                    .background(colors.attention, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                    color = colors.canvasDeep,
                                    style = HermesTheme.typography.metadataStrong.copy(
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                    ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Dimens.GapXs))
                    Text(
                        text = destination.title,
                        color = tint,
                        style = HermesTheme.typography.label.copy(fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

private val RootDestination.navigationIcon: ImageVector
    get() = when (this) {
        RootDestination.FLEET -> Icons.AutoMirrored.Outlined.List
        RootDestination.PULSE -> Icons.Outlined.Notifications
        RootDestination.CHATS -> ChatsIcon
        RootDestination.SETTINGS -> Icons.Outlined.Settings
    }

private val ChatsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Chats",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6.5f, 17.5f)
            lineTo(3f, 20f)
            lineTo(3.8f, 15.8f)
            curveTo(2.7f, 14.5f, 2f, 12.8f, 2f, 11f)
            curveTo(2f, 6.6f, 6.2f, 3f, 11.5f, 3f)
            curveTo(16.7f, 3f, 21f, 6.6f, 21f, 11f)
            curveTo(21f, 15.4f, 16.7f, 19f, 11.5f, 19f)
            curveTo(9.7f, 19f, 8f, 18.5f, 6.5f, 17.5f)
            moveTo(7f, 9f)
            lineTo(16f, 9f)
            moveTo(7f, 13f)
            lineTo(13f, 13f)
        }
    }.build()
}
