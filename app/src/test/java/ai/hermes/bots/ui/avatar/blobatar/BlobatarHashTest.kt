package ai.hermes.bots.ui.avatar.blobatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobatarHashTest {
    @Test
    fun `normalization is NFC trimmed and locale independent lowercase`() {
        assertEquals("caf\u00e9", normalizeBlobatarSeed("  CAFE\u0301  "))
        assertEquals("ελλάδα", normalizeBlobatarSeed("Ελλάδα"))
        assertEquals("日本語", normalizeBlobatarSeed("日本語"))
        assertEquals("أحمد", normalizeBlobatarSeed("أحمد"))
        assertEquals("🦊🐻", normalizeBlobatarSeed("🦊🐻"))
    }

    @Test
    fun `hash states and keyed streams match upstream v2_7_0 vectors`() {
        assertVector("alain", 2_106_776_898, 0.1506984510924667, 0.315505497623235)
        assertVector("caf\u00e9", -856_785_098, 0.4011212137993425, 0.29332484561018646)
        assertVector("日本語", 671_187_053, 0.039473693585023284, 0.7762340207118541)
        assertVector("Ελλάδα", -750_521_746, 0.6319990218617022, 0.8135531879961491)
        assertVector("أحمد", 988_240_758, 0.06318627507425845, 0.23711295914836228)
        assertVector("🦊🐻", 640_387_996, 0.648136583622545, 0.6644966946914792)
    }

    @Test
    fun `key streams are stable independent and respect raw mode`() {
        val state = blobatarSeedState("alain")
        assertNotEquals(blobatarStream(state, "shape"), blobatarStream(state, "hue"))
        assertNotEquals(
            blobatarSeedState("Alain", normalize = false),
            blobatarSeedState("alain", normalize = false),
        )
        assertTrue(blobatarStream(state, "shape") in 0.0..<1.0)
    }

    private fun assertVector(seed: String, state: Int, shape: Double, hue: Double) {
        assertEquals(state, blobatarSeedState(seed))
        assertEquals(shape, blobatarStream(state, "shape"), 0.0)
        assertEquals(hue, blobatarStream(state, "hue"), 0.0)
    }
}
