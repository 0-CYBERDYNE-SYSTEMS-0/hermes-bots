package ai.hermes.bots.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotAccentTest {

  @Test
  fun `same name maps to the same hue`() {
    assertEquals(BotAccent.hueOf("scout"), BotAccent.hueOf("scout"))
  }

  @Test
  fun `hue is stable across case and surrounding whitespace`() {
    assertEquals(BotAccent.hueOf("scout"), BotAccent.hueOf("SCOUT"))
    assertEquals(BotAccent.hueOf("scout"), BotAccent.hueOf("  scout "))
  }

  @Test
  fun `hue stays in range`() {
    for (name in listOf("scout", "default", "recon", "", "a bot with spaces")) {
      val hue = BotAccent.hueOf(name)
      assertTrue("$name -> $hue", hue in 0f..360f)
    }
  }

  @Test
  fun `bot names spread across the hue wheel`() {
    val hues = listOf("scout", "default", "recon", "inbox", "ship", "researcher", "night")
      .map { BotAccent.hueOf(it) }
      .distinct()
    assertTrue("expected spread, got $hues", hues.size >= 5)
  }
}
