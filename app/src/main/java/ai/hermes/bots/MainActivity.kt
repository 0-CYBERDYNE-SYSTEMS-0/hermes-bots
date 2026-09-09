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
import ai.hermes.bots.ui.AppRoot
import ai.hermes.bots.ui.DeepLinkLaunch
import ai.hermes.bots.ui.theme.HermesBotsTheme

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Last provisioning deep link + arrival seq (so a repeated identical URI still fires). */
    private val deepLink = mutableStateOf<DeepLinkLaunch?>(null)
    private var linkSeq = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        captureDeepLink(intent)
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
                AppRoot(deepLink = deepLink.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureDeepLink(intent)
    }

    private fun captureDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "hermesbots") return
        linkSeq += 1
        deepLink.value = DeepLinkLaunch(uri = data.toString(), seq = linkSeq)
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
