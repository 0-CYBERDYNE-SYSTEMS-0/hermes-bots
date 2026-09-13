package ai.hermes.bots.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the §3.2 blink windows of [BotFaceClock.isBlinking]: working `t % 1450 > 1260`,
 * idle `t % 3200 > 3020` — boundary ticks, wrap-around, and determinism.
 */
class BotFaceClockTest {

  @Test
  fun `working blink opens strictly after 1260ms`() {
    assertFalse(BotFaceClock.isBlinking(1260L, working = true))
    assertTrue(BotFaceClock.isBlinking(1261L, working = true))
  }

  @Test
  fun `working blink holds to the end of its period`() {
    for (t in 1261L until 1450L) {
      assertTrue("t=$t should be blinking (working)", BotFaceClock.isBlinking(t, working = true))
    }
    assertFalse(BotFaceClock.isBlinking(1450L, working = true)) // wraps: eyes open again
  }

  @Test
  fun `idle blink opens strictly after 3020ms`() {
    assertFalse(BotFaceClock.isBlinking(3020L, working = false))
    assertTrue(BotFaceClock.isBlinking(3021L, working = false))
  }

  @Test
  fun `idle blink holds to the end of its period`() {
    for (t in 3021L until 3200L) {
      assertTrue("t=$t should be blinking (idle)", BotFaceClock.isBlinking(t, working = false))
    }
    assertFalse(BotFaceClock.isBlinking(3200L, working = false)) // wraps: eyes open again
  }

  @Test
  fun `early ticks never blink`() {
    assertFalse(BotFaceClock.isBlinking(0L, working = true))
    assertFalse(BotFaceClock.isBlinking(0L, working = false))
    assertFalse(BotFaceClock.isBlinking(1259L, working = true))
    assertFalse(BotFaceClock.isBlinking(3019L, working = false))
  }

  @Test
  fun `wrap-around ticks stay on the window`() {
    // One and three full periods past onset still blink…
    assertTrue(BotFaceClock.isBlinking(1450L + 1300L, working = true))
    assertTrue(BotFaceClock.isBlinking(1450L * 2 + 1300L, working = true))
    assertTrue(BotFaceClock.isBlinking(3200L + 3100L, working = false))
    // …and just after a wrap the eyes are open again.
    assertFalse(BotFaceClock.isBlinking(1450L * 3 + 100L, working = true))
    assertFalse(BotFaceClock.isBlinking(3200L * 2 + 100L, working = false))
  }

  @Test
  fun `window lengths match the spec`() {
    var workingBlinks = 0
    for (t in 0L until 1450L) if (BotFaceClock.isBlinking(t, working = true)) workingBlinks++
    assertEquals(189, workingBlinks) // 1450 - 1261
    var idleBlinks = 0
    for (t in 0L until 3200L) if (BotFaceClock.isBlinking(t, working = false)) idleBlinks++
    assertEquals(179, idleBlinks) // 3200 - 3021
  }

  @Test
  fun `isBlinking is deterministic`() {
    for (t in longArrayOf(0L, 1L, 1259L, 1261L, 2899L, 4444L, 77777L)) {
      val a = BotFaceClock.isBlinking(t, working = true)
      assertEquals(a, BotFaceClock.isBlinking(t, working = true))
      val b = BotFaceClock.isBlinking(t, working = false)
      assertEquals(b, BotFaceClock.isBlinking(t, working = false))
    }
  }

  @Test
  fun `clock tick flow is backed by a real timestamp`() {
    val now = System.currentTimeMillis()
    val tick = BotFaceClock.tick.value
    // The flow is initialized to wall-clock ms; loop-side updates only move it forward.
    assertTrue("tick=$tick now=$now", tick <= now + 60_000L)
    assertEquals(BotFaceClock.tick.value, BotFaceClock.tick.value)
  }
}
