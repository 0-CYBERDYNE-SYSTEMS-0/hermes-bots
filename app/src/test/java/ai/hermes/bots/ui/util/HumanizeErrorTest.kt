package ai.hermes.bots.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R11 (MODEL-UX-PUNCHLIST.md rev 2): editor save-flow error mappings. */
class HumanizeErrorTest {

  @Test
  fun `invalid profile name maps to the name rules`() {
    assertEquals(
      "Bot names can use lowercase letters, numbers, dashes and underscores.",
      Humanize.friendlyError("rpc 4062: Invalid profile name 'My Bot!'", "this bot"),
    )
  }

  @Test
  fun `reserved profile name maps to pick another`() {
    assertEquals(
      "That name is reserved by the gateway — pick another.",
      Humanize.friendlyError("Profile 'test' is reserved", "this bot"),
    )
  }

  @Test
  fun `duplicate profile maps to already exists`() {
    assertEquals(
      "A bot with that name already exists — open it from the roster.",
      Humanize.friendlyError("rpc 4062: Profile 'validator' already exists", "this bot"),
    )
  }

  @Test
  fun `missing profile maps to gone from roster`() {
    assertEquals(
      "That bot is gone — refresh the roster.",
      Humanize.friendlyError("rpc 4064: profile 'ghost' not found", "ghost"),
    )
  }

  @Test
  fun `unknown provider maps to gateway lacks provider`() {
    assertEquals(
      "This gateway doesn't have that provider.",
      Humanize.friendlyError("agent init failed: Unknown provider 'custom:mystery'", "this bot"),
    )
  }

  @Test
  fun `session cap maps to try shortly`() {
    assertEquals(
      "The gateway is at its live-session cap — try again shortly.",
      Humanize.friendlyError("rpc 4090: active session limit reached", "this bot"),
    )
    assertEquals(
      "The gateway is at its live-session cap — try again shortly.",
      Humanize.friendlyError("gateway reports active session cap", "this bot"),
    )
  }

  @Test
  fun `internal error maps to try again`() {
    assertEquals(
      "The gateway hit an internal error — try again.",
      Humanize.friendlyError("rpc -32603: internal error while building agent", "this bot"),
    )
  }

  @Test
  fun `pre-existing mappings still win for their own strings`() {
    assertEquals(
      "That model ID was rejected — check scout's model in Edit bot.",
      Humanize.friendlyError("HTTP 400: not a valid model ID", "scout"),
    )
  }

  @Test
  fun `unknown raw errors still return null`() {
    assertNull(Humanize.friendlyError("totally exotic failure", "scout"))
    assertNull(Humanize.friendlyError(null, "scout"))
    assertNull(Humanize.friendlyError("", "scout"))
  }

  @Test
  fun `submit failure leads humane with the bot name`() {
    assertEquals(
      "Couldn't send that to scout — the gateway rejected the request.",
      Humanize.friendlyError("submit failed (4007): unknown profile", "scout"),
    )
  }

  @Test
  fun `already humane banners pass through untouched`() {
    val banner = "Couldn't start a fresh chat — try again."
    assertEquals(banner, Humanize.friendlyError(banner, "scout"))
    assertEquals("Couldn't load the conversation.", Humanize.friendlyError("Couldn't load the conversation.", "scout"))
    assertEquals("Couldn't send that response.", Humanize.friendlyError("Couldn't send that response.", "scout"))
  }

  @Test
  fun `server error starting with couldn't still maps humanely`() {
    // The pass-through is scoped to the app-authored banners — a SERVER error string that
    // happens to start "couldn't" must still hit the mapping table.
    assertEquals(
      "The gateway dropped the connection — retrying.",
      Humanize.friendlyError("couldn't reach upstream: Connection refused", "scout"),
    )
    assertEquals(
      "The gateway hit an internal error — try again.",
      Humanize.friendlyError("couldn't complete request: internal error", "scout"),
    )
  }
}
