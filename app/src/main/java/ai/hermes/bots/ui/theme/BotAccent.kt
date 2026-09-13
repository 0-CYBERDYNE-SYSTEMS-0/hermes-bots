package ai.hermes.bots.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Deterministic per-bot accent (UI-SPEC.md §3.1): a stable hue hashed from the bot's name,
 * rendered at brand-compatible saturation/lightness per theme. The same name maps to the
 * same accent on every surface (roster dots, chat header, bubble edges); it is an identity
 * color, not a uniqueness guarantee. Brand chrome (primary/unread) stays powder blue +
 * burnt orange — see Theme.kt.
 */
object BotAccent {

  /** Stable 0..360 hue from the bot's display name (FNV-1a; not String.hashCode — JVM-stable). */
  fun hueOf(name: String): Float {
    var h = -2128831035 // 0x811c9dc5
    for (ch in name.lowercase().trim()) {
      h = h xor ch.code
      h *= 16777619
    }
    return (((h % 360) + 360) % 360).toFloat()
  }

  fun dark(name: String): Color = Color.hsl(hueOf(name), 0.46f, 0.70f)

  fun light(name: String): Color = Color.hsl(hueOf(name), 0.52f, 0.40f)

  fun color(name: String, darkTheme: Boolean): Color = if (darkTheme) dark(name) else light(name)
}
