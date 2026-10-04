package ai.hermes.bots.ui.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSendDraftTest {

    @Test
    fun `retrying unchanged draft reuses its idempotency key`() {
        val draft = GroupSendDraftState("hello", pendingEventId = "event-1", pendingText = "hello")

        assertEquals("event-1", groupSendEventId(draft, "hello", "event-2"))
    }

    @Test
    fun `editing a pending draft starts a distinct event`() {
        val draft = GroupSendDraftState("hello again", pendingEventId = "event-1", pendingText = "hello")

        assertEquals("event-2", groupSendEventId(draft, "hello again", "event-2"))
    }

    @Test
    fun `only accepted matching send clears its unchanged draft`() {
        val draft = GroupSendDraftState("hello", pendingEventId = "event-1", pendingText = "hello")

        assertEquals(
            GroupSendDraftState(""),
            resolveGroupSendDraft(draft, GroupSendOutcome("event-1", "hello", accepted = true)),
        )
    }

    @Test
    fun `failed or stale send outcome leaves current draft and retry key intact`() {
        val draft = GroupSendDraftState("hello", pendingEventId = "event-1", pendingText = "hello")

        assertEquals(
            draft,
            resolveGroupSendDraft(
                draft,
                GroupSendOutcome("event-1", "hello", accepted = false, deliveryUnknown = true),
            ),
        )
        assertEquals(
            draft,
            resolveGroupSendDraft(draft, GroupSendOutcome("event-old", "hello", accepted = true)),
        )
    }

    @Test
    fun `accepted earlier send does not clear a newer draft`() {
        val draft = GroupSendDraftState("new text", pendingEventId = "event-1", pendingText = "old text")

        assertEquals(
            GroupSendDraftState("new text"),
            resolveGroupSendDraft(draft, GroupSendOutcome("event-1", "old text", accepted = true)),
        )
    }

    @Test
    fun `uncertain send after recreation must be retried before an edited draft can send`() {
        val draft = GroupSendDraftState("edited text", pendingEventId = "event-1", pendingText = "original text")
        val unconfirmed = unconfirmedGroupSend(draft, outcome = null, busy = false)

        assertEquals(GroupSendOutcome("event-1", "original text", accepted = false, deliveryUnknown = true), unconfirmed)
        assertFalse(canSubmitGroupDraft(draft.text, unconfirmed))
        assertTrue(canSubmitGroupDraft("original text", unconfirmed))
    }

    @Test
    fun `retry result clears uncertain tracking only after acceptance`() {
        val draft = GroupSendDraftState("edited text", pendingEventId = "event-1", pendingText = "original text")

        assertEquals(
            draft,
            resolveGroupSendDraft(
                draft,
                GroupSendOutcome("event-1", "original text", accepted = false, deliveryUnknown = true),
            ),
        )
        assertEquals(
            GroupSendDraftState("edited text"),
            resolveGroupSendDraft(
                draft,
                GroupSendOutcome("event-1", "original text", accepted = true),
            ),
        )
    }
}
