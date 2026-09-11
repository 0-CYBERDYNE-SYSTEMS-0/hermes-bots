package ai.hermes.bots

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import ai.hermes.bots.notify.BotNotifier
import ai.hermes.bots.ui.AppRoot
import ai.hermes.bots.ui.DeepLinkLaunch
import ai.hermes.bots.ui.PendingChatLaunch
import ai.hermes.bots.ui.theme.HermesBotsTheme

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Last provisioning deep link + arrival seq (so a repeated identical URI still fires). */
    private val deepLink = mutableStateOf<DeepLinkLaunch?>(null)
    private var linkSeq = 0L

    /** Bot-notification tap → one-shot chat navigation, same consume-once shape as deepLink (SV-15). */
    private val pendingChat = mutableStateOf<PendingChatLaunch?>(null)
    private var chatSeq = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        // Only on a fresh launch: recapturing after recreation would re-open the dialog
        // (or re-fire the chat jump) the user may have already dismissed. Rotating while
        // the provisioning dialog is open dismisses it — accepted.
        if (savedInstanceState == null) {
            captureDeepLink(intent)
            capturePendingChat(intent)
        }
        enableEdgeToEdge()
        setContent {
            val themeMode by (application as HermesBotsApp).graph.settings.themeMode.collectAsState()
            HermesBotsTheme(
                darkTheme = when (themeMode) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                },
            ) {
                AppRoot(deepLink = deepLink.value, pendingChat = pendingChat.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureDeepLink(intent)
        capturePendingChat(intent)
    }

    private fun captureDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "hermesbots") return
        linkSeq += 1
        deepLink.value = DeepLinkLaunch(uri = data.toString(), seq = linkSeq)
    }

    private fun capturePendingChat(intent: Intent?) {
        val connectionId = intent?.getStringExtra(BotNotifier.EXTRA_CONNECTION_ID) ?: return
        val botName = intent.getStringExtra(BotNotifier.EXTRA_BOT_NAME) ?: return
        chatSeq += 1
        pendingChat.value = PendingChatLaunch(connectionId, botName, chatSeq)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }
}
