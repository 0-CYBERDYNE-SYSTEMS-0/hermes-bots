package ai.hermes.bots.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Designed empty moment (audit A7): centered avatar slot + title + instructive body + CTA. */
@Composable
fun EmptyState(
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  avatar: (@Composable () -> Unit)? = null,
  cta: (@Composable () -> Unit)? = null,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    avatar?.invoke()
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Text(
      body,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    cta?.invoke()
  }
}
