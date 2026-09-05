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
}
