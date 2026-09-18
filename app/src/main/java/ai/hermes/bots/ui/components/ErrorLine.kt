package ai.hermes.bots.ui.components

import ai.hermes.bots.ui.util.Humanize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * Q4 (QA 2026-09-14): unmapped raws fall back to a generic humane line instead of
 * rendering exception/protocol text up front; the raw text moves behind Details.
 *
 * Incident 2026-09-16 ("nothing was dismissible"): all optional extras default to null,
 * so existing call sites are unchanged.
 * - [onDismiss] renders a small X that removes the line (client-side artifacts only —
 *   server history never replays them).
 * - [actionLabel]/[onAction] render one tappable action under the first line (the
 *   turn-stall notice uses "Interrupt").
 * - [verbatim] shows [raw] as the first line unmapped — for server warnings and other
 *   already-humane app lines where the generic fallback would be wrong. With
 *   firstLine == raw the Details row hides itself, as before.
 */
@Composable
fun ErrorLine(
  raw: String,
  botName: String,
  modifier: Modifier = Modifier,
  verbatim: Boolean = false,
  onDismiss: (() -> Unit)? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  var showDetails by remember(raw) { mutableStateOf(false) }
  val friendly = if (verbatim) raw else Humanize.friendlyError(raw, botName)
  val firstLine = friendly ?: "Something went wrong — try again."
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
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            firstLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          if (onDismiss != null) {
            // 48 dp target (fleet-pulse-ui-spec §2); the icon is the visual only.
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
              Icon(
                Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
        if (actionLabel != null && onAction != null) {
          Text(
            actionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
              .clickable(onClick = onAction)
              .padding(horizontal = 8.dp, vertical = 6.dp),
          )
        }
        if (firstLine != raw) {
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
