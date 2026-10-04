package ai.hermes.bots.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * When a bot name appears on multiple gateways, show its owning connection's label as a quiet chip.
 * Shown wherever a bot name that exists on more than one gateway renders (roster row, chat
 * header, member pickers, AnyChat transcript); AnyChat shows it on every member tag.
 */
@Composable
fun ConnectionChip(
  label: String,
  modifier: Modifier = Modifier,
) {
  Text(
    label,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    modifier = modifier
      .clip(MaterialTheme.shapes.extraSmall)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .padding(horizontal = 6.dp, vertical = 1.dp),
  )
}
