package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TurnWatchdog policy (incident 2026-09-16): staleness is event silence, never run time.
 * Live dogfood calibration (same day): a plain `sleep 120` tool run emits no events for its
 * whole duration, so the 60 s mark must stay advisory (SOFT) and only the server-budget
 * 600 s mark may release the composer (HARD).
 */
class TurnWatchdogTest {

  private val t0 = 1_000_000L

  @Test
  fun `fresh activity stays in none`() {
    // Deltas/tool events arriving every few seconds keep the turn alive indefinitely.
    assertEquals(
      TurnWatchdog.Phase.NONE,
      TurnWatchdog.phase(streaming = true, alreadyStalled = false, lastActivityMs = t0, nowMs = t0 + 5_000L),
    )
    assertEquals(
      TurnWatchdog.Phase.NONE,
      TurnWatchdog.phase(
        streaming = true,
        alreadyStalled = false,
        lastActivityMs = t0,
        nowMs = t0 + TurnWatchdog.SOFT_NOTICE_MS - 1,
      ),
    )
  }

  @Test
  fun `a minute of silence is advisory only`() {
    // SOFT must never release the composer — the sleep-120 false positive live test.
    assertEquals(
      TurnWatchdog.Phase.SOFT,
      TurnWatchdog.phase(
        streaming = true,
        alreadyStalled = false,
        lastActivityMs = t0,
        nowMs = t0 + TurnWatchdog.SOFT_NOTICE_MS,
      ),
    )
    assertEquals(
      TurnWatchdog.Phase.SOFT,
      TurnWatchdog.phase(
        streaming = true,
        alreadyStalled = false,
        lastActivityMs = t0,
        nowMs = t0 + TurnWatchdog.HARD_STALL_MS - 1,
      ),
    )
  }

  @Test
  fun `silence past the server turn budget declares a stall`() {
    assertEquals(
      TurnWatchdog.Phase.HARD,
      TurnWatchdog.phase(
        streaming = true,
        alreadyStalled = false,
        lastActivityMs = t0,
        nowMs = t0 + TurnWatchdog.HARD_STALL_MS,
      ),
    )
  }

  @Test
  fun `not streaming never escalates`() {
    // A finished turn (complete/cancelled/error) must never be declared stalled.
    assertEquals(
      TurnWatchdog.Phase.NONE,
      TurnWatchdog.phase(
        streaming = false,
        alreadyStalled = false,
        lastActivityMs = t0,
        nowMs = t0 + 10 * TurnWatchdog.HARD_STALL_MS,
      ),
    )
  }

  @Test
  fun `an already stalled turn does not re-declare`() {
    // The declaration is one-shot per silence window; the VM re-arms only via real activity.
    assertEquals(
      TurnWatchdog.Phase.NONE,
      TurnWatchdog.phase(
        streaming = true,
        alreadyStalled = true,
        lastActivityMs = t0,
        nowMs = t0 + 10 * TurnWatchdog.HARD_STALL_MS,
      ),
    )
  }

  @Test
  fun `event after stall clears stale notices and keeps them gone`() {
    val items = listOf(
      ChatItem("u1", ItemKind.USER, "count to 15"),
      ChatItem("a1", ItemKind.ASSISTANT, "1, 2, 3…", streaming = true),
    )
    val stalled = ChatStream.stallNotice(items) { "stall-1" }
    assertEquals(1, stalled.count { it.id.startsWith(ChatStream.STALL_ID_PREFIX) })
    // Any late session event after the stall proves the notice stale — it must drop…
    val resumed = ChatStream.clearStallNotices(stalled)
    assertEquals(items, resumed)
    // …and repeated clears stay idempotent.
    assertEquals(items, ChatStream.clearStallNotices(resumed))
  }

  @Test
  fun `quiet notice appears once and never stacks`() {
    val items = listOf(ChatItem("a1", ItemKind.ASSISTANT, "working…", streaming = true))
    val quieted = ChatStream.quietNotice(items) { "quiet-1" }
    assertEquals(1, quieted.count { it.id.startsWith(ChatStream.QUIET_ID_PREFIX) })
    // The VM ticker re-runs every 10 s — the second SOFT verdict must not append a twin.
    val again = ChatStream.quietNotice(quieted) { "quiet-2" }
    assertEquals(quieted, again)
  }

  @Test
  fun `hard stall notice replaces an existing quiet notice`() {
    val items = listOf(ChatItem("a1", ItemKind.ASSISTANT, "working…", streaming = true))
    val quieted = ChatStream.quietNotice(items) { "quiet-1" }
    val stalled = ChatStream.stallNotice(quieted) { "stall-1" }
    // The HARD declaration appends its own line; clearStallNotices sweeps BOTH prefixes so
    // late activity leaves no stale watchdog residue of either kind.
    assertEquals(1, stalled.count { it.id.startsWith(ChatStream.STALL_ID_PREFIX) })
    assertEquals(items, ChatStream.clearStallNotices(stalled))
  }

  @Test
  fun `quiet notice coexists with unrelated error lines`() {
    // Dismissed-tool errors etc. share ItemKind.ERROR; only watchdog id prefixes gate stacking.
    val items = listOf(
      ChatItem("u1", ItemKind.USER, "run the build"),
      ChatItem("e-1", ItemKind.ERROR, "steer didn't reach bot — try again."),
    )
    val quieted = ChatStream.quietNotice(items) { "quiet-9" }
    assertEquals(1, quieted.count { it.id.startsWith(ChatStream.QUIET_ID_PREFIX) })
    assertEquals("quiet-9", quieted.last().id)
    // Sweeping watchdog lines leaves the unrelated error untouched.
    val swept = ChatStream.clearStallNotices(quieted)
    assertEquals(items, swept)
  }
}
