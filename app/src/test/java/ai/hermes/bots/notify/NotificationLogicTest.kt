package ai.hermes.bots.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLogicTest {
    @Test
    fun `channel id is stable`() {
        assertEquals("bot_messages", BotNotifier.CHANNEL_ID)
    }

    @Test
    fun `preview truncation contract`() {
        val long = "x".repeat(500)
        assertTrue(long.take(180).length <= 180)
    }

    @Test
    fun `approval notification keeps every real choice when it fits`() {
        val choices = listOf("once", "session", "deny")
        val visible = BotNotifier.approvalActionChoices(choices)
        assertEquals(choices, visible)
        assertTrue(visible.contains("deny"))
        assertTrue(visible.size <= BotNotifier.MAX_APPROVAL_ACTIONS)
    }

    @Test
    fun `approval action cap preserves deny and real order`() {
        val choices = listOf("once", "deny", "custom", "later")
        assertEquals(
            listOf("once", "deny", "custom"),
            BotNotifier.approvalActionChoices(choices),
        )
        assertEquals(
            listOf("once", "session", "deny"),
            BotNotifier.approvalActionChoices(listOf("once", "session", "always", "deny")),
        )
    }
}
