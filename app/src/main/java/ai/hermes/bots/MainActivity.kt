package ai.hermes.bots

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.hermes.bots.ui.AppRoot
import ai.hermes.bots.ui.theme.HermesBotsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesBotsTheme {
                AppRoot()
            }
        }
    }
}
