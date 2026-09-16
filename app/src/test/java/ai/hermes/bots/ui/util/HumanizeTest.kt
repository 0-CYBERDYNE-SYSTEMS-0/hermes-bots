package ai.hermes.bots.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HumanizeTest {

  @Test
  fun `model slug humanizes known parts`() {
    assertEquals("DeepSeek V4 Flash", Humanize.model("deepseek-v4-flash"))
    assertEquals("Claude Sonnet", Humanize.model("claude-sonnet"))
    assertEquals("GPT 4.1 Mini", Humanize.model("gpt-4.1-mini"))
  }

  @Test
  fun `model slug handles provider paths and unknown tokens`() {
    assertEquals("DeepSeek V4 Flash", Humanize.model("cline-pass/deepseek-v4-flash"))
    assertEquals("Llama 3", Humanize.model("llama-3"))
    assertEquals("Zorg Bot", Humanize.model("zorg-bot"))
  }

  @Test
  fun `model null and blank fall back to null`() {
    assertNull(Humanize.model(null))
    assertNull(Humanize.model(""))
    assertNull(Humanize.model("   "))
  }

  @Test
  fun `friendly error maps known cases`() {
    assertEquals(
      "That model ID was rejected — check scout's model in Edit bot.",
      Humanize.friendlyError("HTTP 400: not a valid model ID", "scout"),
    )
    assertEquals(
      "The gateway dropped the connection — retrying.",
      Humanize.friendlyError("connection reset while streaming", "scout"),
    )
    assertEquals(
      "Sign-in failed — check the session token in Gateways.",
      Humanize.friendlyError("HTTP 401 unauthorized", "scout"),
    )
  }

  @Test
  fun `friendly error returns null for unknown or blank`() {
    assertNull(Humanize.friendlyError("totally exotic failure", "scout"))
    assertNull(Humanize.friendlyError(null, "scout"))
    assertNull(Humanize.friendlyError("", "scout"))
  }

  @Test
  fun `common cron shapes humanize`() {
    assertEquals("Every day at 9 AM", Humanize.cron("0 9 * * *"))
    assertEquals("Every day at 8:30 PM", Humanize.cron("30 20 * * *"))
    assertEquals("Every 30 min", Humanize.cron("*/30 * * * *"))
    assertEquals("Weekdays at 6 PM", Humanize.cron("0 18 * * 1-5"))
    assertEquals("Every Monday at 7:15 AM", Humanize.cron("15 7 * * 1"))
    assertEquals("Hourly at :15", Humanize.cron("15 * * * *"))
  }

  @Test
  fun `unknown cron and natural language pass through`() {
    assertEquals("5,15 9 * * 1,3", Humanize.cron("5,15 9 * * 1,3"))
    assertEquals("every 30m", Humanize.cron("every 30m"))
    assertEquals("0 9", Humanize.cron("0 9"))
  }

  @Test
  fun `tool labels humanize known slugs`() {
    // Q8 (QA 2026-09-14): chip titles / Running lines never show raw snake_case.
    assertEquals("Terminal", Humanize.toolLabel("terminal"))
    assertEquals("Read file", Humanize.toolLabel("read_file"))
    assertEquals("Message agent", Humanize.toolLabel("message_agent"))
    assertEquals("Web search", Humanize.toolLabel("web_search"))
    assertEquals("Analyze image", Humanize.toolLabel("vision_analyze"))
  }

  @Test
  fun `tool labels fall back to title case and handle blanks`() {
    assertEquals("Deep Research", Humanize.toolLabel("deep_research"))
    assertEquals("Tool", Humanize.toolLabel("tool"))
    assertEquals("Tool", Humanize.toolLabel(""))
    assertEquals("Tool", Humanize.toolLabel(null))
  }
}
