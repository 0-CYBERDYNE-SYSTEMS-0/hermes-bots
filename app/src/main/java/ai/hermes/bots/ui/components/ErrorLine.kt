package ai.hermes.bots.ui.components

import ai.hermes.bots.ui.util.Humanize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


/**
 * The one inline error system-line (audit A28): centered pill, humane first line,
 * raw detail hidden behind a "Details" tap — never raw HTTP as the first line.
 */
@Composable
fun ErrorLine(
  raw: String,
  botName: String,
  modifier: Modifier = Modifier,
) {
  var showDetails by remember(raw) { mutableStateOf(false) }
  val friendly = Humanize.friendlyError(raw, botName)
  Column(
    modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainer,
      shape = MaterialTheme.shapes.small,
    ) {
      Column(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          friendly ?: raw,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
        if (friendly != null && friendly != raw) {
          Text(
            if (showDetails) "Hide details" else "Details",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .clickable { showDetails = !showDetails }
              .padding(horizontal = 8.dp, vertical = 6.dp),
          )
          if (showDetails) {
            Text(
              raw,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
