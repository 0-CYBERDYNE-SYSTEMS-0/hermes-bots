package ai.hermes.bots.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinned approval card lifetime (incident 2026-09-16: cards were pinned forever). */
class ApprovalPinPolicyTest {

  private val t0 = 5_000_000L

  @Test
  fun `expired card lingers the full window then auto dismisses`() {
    assertFalse(ApprovalPinPolicy.expiredLingerDone(t0, t0 + ApprovalPinPolicy.EXPIRED_LINGER_MS - 1))
    assertTrue(ApprovalPinPolicy.expiredLingerDone(t0, t0 + ApprovalPinPolicy.EXPIRED_LINGER_MS))
  }

  @Test
  fun `active card is always visible`() {
    assertTrue(
      ApprovalPinPolicy.visible(hasCard = true, active = true, resolved = null, expired = false, dismissed = false),
    )
  }

  @Test
  fun `resolved card stays until the user dismisses it`() {
    assertTrue(
      ApprovalPinPolicy.visible(hasCard = true, active = false, resolved = "once", expired = false, dismissed = false),
    )
    assertFalse(
      ApprovalPinPolicy.visible(hasCard = true, active = false, resolved = "once", expired = false, dismissed = true),
    )
  }

  @Test
  fun `expired card shows during the linger window and hides after dismissal`() {
    assertTrue(
      ApprovalPinPolicy.visible(hasCard = true, active = false, resolved = null, expired = true, dismissed = false),
    )
    // User need not wait out the timer — the X dismisses immediately.
    assertFalse(
      ApprovalPinPolicy.visible(hasCard = true, active = false, resolved = null, expired = true, dismissed = true),
    )
  }

  @Test
  fun `nothing renders without a card`() {
    assertFalse(
      ApprovalPinPolicy.visible(hasCard = false, active = false, resolved = null, expired = false, dismissed = false),
    )
  }
}
