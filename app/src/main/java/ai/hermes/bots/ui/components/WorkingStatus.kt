package ai.hermes.bots.ui.components

import ai.hermes.bots.data.AvatarImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The "single current-action line" (audit A29, Grok brief §6): tiny avatar +
 * "Working — {status}" + pulsing dot, shown above the composer while a turn runs.
 * Optional [accent] (UI-SPEC §3.1): the per-bot accent tints the pulsing dot; when null
 * the default brand secondary is used, exactly as before.
 */
@Composable
fun WorkingStatus(
  botName: String,
  status: String,
  avatar: AvatarImage?,
  modifier: Modifier = Modifier,
  accent: Color? = null,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FaceAvatar(botName, 20.dp, real = avatar)
    Text(
      "Working — $status",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    PulsingDot(dotSize = 8.dp, color = accent ?: MaterialTheme.colorScheme.secondary)
  }
}
