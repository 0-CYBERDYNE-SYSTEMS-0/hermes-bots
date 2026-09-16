package ai.hermes.bots.ui.avatar.blobatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobatarExpressionTest {
    @Test
    fun `generation two exposes all fourteen upstream expressions`() {
        assertEquals(14, BlobatarExpression.values().size)
        assertEquals(
            setOf(
                "Idle", "Happy", "Sad", "Mad", "Surprised", "Wink", "Sleepy",
                "Smug", "Unsure", "Scared", "Love", "Shy", "Sick", "Thinking",
            ),
            BlobatarExpression.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun `pose channels match upstream generation two fixtures`() {
        val happy = BlobatarExpression.Happy.pose()
        assertEquals(1.72, happy.eyeScaleX, 0.0)
        assertEquals(0.3, happy.eyeScaleY, 0.0)
        assertEquals(8.0, happy.tilt, 0.0)
        assertEquals(-2.2, happy.bodyOffsetY, 0.0)

        val thinking = BlobatarExpression.Thinking.pose()
        assertEquals(-8.4, thinking.rightEyeOffsetY, 0.0)
        assertEquals(0.8, thinking.rock, 0.0)
        assertEquals(1.0, thinking.lockSeededTilt, 0.0)
    }

    @Test
    fun `expression changes do not mutate resolved seed layout`() {
        val idle = resolveBlobatar("alain").layout
        val thinking = resolveBlobatar("alain").layout
        assertEquals(idle.body, thinking.body)
        assertEquals(idle.eyes, thinking.eyes)
        assertNotEquals(
            BlobatarExpression.Idle.pose(),
            BlobatarExpression.Thinking.pose(),
        )
        assertTrue(BlobatarExpression.Idle.pose().heat == 0.0)
    }
}

