package ai.hermes.bots.ui.avatar.blobatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobatarMotionTest {
    @Test
    fun `static mode is a stable open frame`() {
        val first = blobatarMotionFrame("alain", 0L, BlobatarMotionMode.Static)
        val later = blobatarMotionFrame("alain", 9_999L, BlobatarMotionMode.Static)
        assertEquals(first, later)
        assertEquals(1.0, first.blink, 0.0)
        assertEquals(0.0, first.bob, 0.0)
    }

    @Test
    fun `motion seeds are deterministic and independent from layout traits`() {
        assertEquals(blobatarMotionSeeds("alain"), blobatarMotionSeeds("ALAIN"))
        assertNotEquals(blobatarMotionSeeds("alain"), blobatarMotionSeeds("other"))
        val layout = resolveBlobatar("alain").layout
        val frame = blobatarMotionFrame("alain", 1_234L, BlobatarMotionMode.Ambient)
        assertEquals(2, layout.eyes.size)
        assertTrue(frame.breathe.x in 0.97..1.03)
        assertTrue(frame.breathe.y in 0.97..1.03)
        assertTrue(frame.blink in 0.08..1.0)
    }

    @Test
    fun `one shot completion is bounded to nine hundred milliseconds`() {
        val start = blobatarMotionFrame("alain", 0L, BlobatarMotionMode.OneShot)
        val end = blobatarMotionFrame("alain", 9_999L, BlobatarMotionMode.OneShot)
        assertEquals(0.0, start.progress, 0.0)
        assertEquals(1.0, end.progress, 0.0)
        assertEquals(1.0, end.breathe.x, 1e-9)
        assertEquals(1.0, end.breathe.y, 1e-9)
    }
}

