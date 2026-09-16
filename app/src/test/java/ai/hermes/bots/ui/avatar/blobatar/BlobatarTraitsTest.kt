package ai.hermes.bots.ui.avatar.blobatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobatarTraitsTest {
    @Test
    fun `derived readers and clamping match upstream`() {
        val traits = BlobatarTraits(
            "alain",
            overrides = mapOf(
                "zero" to fixedTrait(0.0),
                "middle" to fixedTrait(0.5),
                "high" to fixedTrait(1.0),
                "nan" to fixedTrait(Double.NaN),
            ),
        )

        assertEquals(10.0, traits.number("zero", 10.0, 20.0), 0.0)
        assertEquals(5, traits.integer("middle", 0, 10))
        assertEquals("z", traits.pick("high", listOf("x", "y", "z")))
        assertEquals(0.0, traits("nan"), 0.0)
        assertTrue(traits.boolean("zero"))
        assertEquals(-4.0, traits.jitter("zero", 4.0), 0.0)
    }

    @Test
    fun `a narrowed list is stable seed selected and leaves neighbors unchanged`() {
        val choices = oneOfTraits(0.11, 0.825, 0.965)
        val plain = BlobatarTraits("alain")
        val narrowed = BlobatarTraits("alain", overrides = mapOf("shape" to choices))
        val withNeighbors = BlobatarTraits(
            "alain",
            overrides = mapOf("shape" to choices, "hue" to fixedTrait(0.5)),
        )

        assertEquals(0.11, narrowed("shape"), 0.0)
        assertEquals(narrowed("shape"), withNeighbors("shape"), 0.0)
        assertEquals(plain("tone"), narrowed("tone"), 0.0)
        assertNotEquals(plain("shape"), narrowed("shape"))
    }

    @Test
    fun `empty narrowing list behaves as an absent override`() {
        val plain = BlobatarTraits("alain")("shape")
        val empty = BlobatarTraits(
            "alain",
            overrides = mapOf("shape" to BlobatarTraitOverride.OneOf(emptyList())),
        )("shape")
        assertEquals(plain, empty, 0.0)
    }
}
