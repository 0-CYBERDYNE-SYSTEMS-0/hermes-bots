package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Steer echo insertion (incident 2026-09-16: steered text never appeared in the transcript). */
class SteerEchoTest {

  @Test
  fun `steer echo appends a user-side item with the marker glyph`() {
    val items = listOf(
      ChatItem("u1", ItemKind.USER, "count to 15"),
      ChatItem("a1", ItemKind.ASSISTANT, "1, 2, 3…", streaming = true),
    )
    val echoed = ChatStream.steerEcho(items, "Skip ahead to 15") { "u-2" }
    assertEquals(items.size + 1, echoed.size)
    val echo = echoed.last()
    assertEquals(ItemKind.USER, echo.kind)
    assertEquals("${ChatStream.STEER_GLYPH}Skip ahead to 15", echo.text)
    // Original list untouched; earlier items keep their ids.
    assertEquals(items, echoed.dropLast(1))
  }

  @Test
  fun `blank steer text never echoes`() {
    val items = listOf(ChatItem("a1", ItemKind.ASSISTANT, "working", streaming = true))
    assertEquals(items, ChatStream.steerEcho(items, "") { "u-9" })
    assertEquals(items, ChatStream.steerEcho(items, "   ") { "u-9" })
  }

  @Test
  fun `each echo gets a fresh id so repeated steers stay distinct rows`() {
    var n = 0
    var items = listOf(ChatItem("a1", ItemKind.ASSISTANT, "1…", streaming = true))
    items = ChatStream.steerEcho(items, "faster") { "u-${++n}" }
    items = ChatStream.steerEcho(items, "even faster") { "u-${++n}" }
    assertTrue(items[1].id != items[2].id)
    assertEquals("${ChatStream.STEER_GLYPH}faster", items[1].text)
    assertEquals("${ChatStream.STEER_GLYPH}even faster", items[2].text)
  }
}
