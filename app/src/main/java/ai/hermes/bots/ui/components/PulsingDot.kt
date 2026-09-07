package ai.hermes.bots.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/**
 * Burnt-orange working dot with a gentle pulse ring (audit A3/A29): outer ring scales
 * 1 → 1.6 and fades over 1200 ms — Grok's "presence you can feel", nothing loud.
 * Optional `borderColor` separates the dot from a busy backdrop (avatar edge).
 */
@Composable
fun PulsingDot(
  modifier: Modifier = Modifier,
  dotSize: Dp = 12.dp,
  color: Color = MaterialTheme.colorScheme.secondary,
  borderColor: Color? = null,
) {
  val transition = rememberInfiniteTransition(label = "working-pulse")
  val scale by transition.animateFloat(
    initialValue = 1f,
    targetValue = 1.6f,
    animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
    label = "pulse-scale",
  )
  val ringAlpha by transition.animateFloat(
    initialValue = 0.5f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
    label = "pulse-alpha",
  )
  Box(
    modifier.size(dotSize * 2f),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .size(dotSize * scale)
        .clip(CircleShape)
        .background(color.copy(alpha = ringAlpha)),
    )
    Box(
      Modifier
        .size(dotSize)
        .clip(CircleShape)
        .background(color)
        .then(
          if (borderColor != null) {
            Modifier.border(2.dp, borderColor, CircleShape)
          } else {
            Modifier
          },
        ),
    )
  }
}
