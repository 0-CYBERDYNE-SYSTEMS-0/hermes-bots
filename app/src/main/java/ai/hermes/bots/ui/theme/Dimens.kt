package ai.hermes.bots.ui.theme

import androidx.compose.ui.unit.dp

/** Standard spacing — one source of truth so gutters stay consistent (audit A31). */
object Dimens {
  /** Screen-edge horizontal gutter for lists/settings (UI-SPEC §2). */
  val GutterScreen = 16.dp

  /** Transcript gutter; bubbles add their own asymmetric margins. */
  val GutterChat = 12.dp

  val Space1 = 4.dp
  val Space2 = 8.dp
  val Space3 = 12.dp
  val Space4 = 16.dp
}
