package ai.hermes.bots.ui.avatar.blobatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobatarColorTest {
    @Test
    fun `palette hex values match upstream fixtures across tone bands`() {
        assertEquals(
            BlobatarPalette("#f8f1f5", "#e5d1dd", "#150c12"),
            blobatarPalette(340.27814148925245, tone = 0.24154494935646653),
        )
        assertEquals(
            BlobatarPalette("#f0f3fa", "#5c7ee9", "#0c0f18"),
            blobatarPalette(267.968887751922, tone = 0.7841790900565684),
        )
        assertEquals(
            BlobatarPalette("#eff4fa", "#2c394a", "#f0f6fd"),
            blobatarPalette(257.3291913885623, tone = 0.9988165574613959),
        )
    }

    @Test
    fun `tone edges select the authored half-open bands`() {
        val expectedHeads = listOf("#ffbfb6", "#ead1ce", "#f18479", "#d7564d", "#ffc3bc", "#49312e")
        val positions = listOf(0.0, 0.2, 0.36, 0.62, 0.8, 0.93)
        assertEquals(expectedHeads, positions.map { blobatarPalette(27.0, tone = it).head })
    }

    @Test
    fun `authored palette clears body and eye contrast floors`() {
        for (hue in 0 until 360) {
            for (tone in listOf(0.1, 0.3, 0.5, 0.7, 0.88, 0.97)) {
                val palette = blobatarPalette(hue.toDouble(), tone = tone)
                val head = hexToBlobatarOklch(palette.head)
                val eye = hexToBlobatarOklch(palette.eye)
                assertTrue("hue=$hue tone=$tone", blobatarContrast(eye, head) >= 4.49)
            }
        }
    }
}
