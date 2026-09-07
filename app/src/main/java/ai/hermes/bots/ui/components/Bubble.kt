package ai.hermes.bots.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Transcript bubble geometry (audits A4/A10): 20 dp round bubbles with a 4 dp
 * "sender corner" pointing at the speaker — assistant top-start, user top-end.
 */
val AssistantBubbleShape = RoundedCornerShape(
  topStart = 4.dp,
  topEnd = 20.dp,
  bottomEnd = 20.dp,
  bottomStart = 20.dp,
)

val UserBubbleShape = RoundedCornerShape(
  topStart = 20.dp,
  topEnd = 4.dp,
  bottomEnd = 20.dp,
  bottomStart = 20.dp,
)
