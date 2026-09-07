package ai.hermes.bots.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Whisper-quiet section subheader (Grok brief §2) — light gray, medium weight. */
@Composable
fun SectionHeader(
  text: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(top = 10.dp, bottom = 2.dp),
  )
}
