package ai.hermes.bots.ui.avatar.blobatar

import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobatarGeometryTest {
    @Test
    fun `all generation-2 silhouette bands are reachable by override`() {
        val bands = listOf(
            0.11 to BlobatarShape.Round,
            0.35 to BlobatarShape.Organic,
            0.54 to BlobatarShape.Boxy,
            0.65 to BlobatarShape.Capsule,
            0.745 to BlobatarShape.Nub,
            0.825 to BlobatarShape.Cloud,
            0.888 to BlobatarShape.Droplet,
            0.933 to BlobatarShape.Hexagon,
            0.965 to BlobatarShape.Sun,
            0.99 to BlobatarShape.Triangle,
        )
        for ((position, expected) in bands) {
            val actual = resolveBlobatar(
                "alain",
                BlobatarOptions(traitOverrides = mapOf("shape" to fixedTrait(position))),
            ).layout.shape
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `representative structured layouts match official Flutter upstream fixture`() {
        val vectors = listOf(
            Vector("  Mixed Case 0  ", BlobatarShape.Boxy, 50.984199908329174, 30.827130092554725, 0, 0),
            Vector("🦊0🌱", BlobatarShape.Capsule, 48.99941568588838, 35.20691194274929, 2, 0),
            Vector("Team Rocket 7", BlobatarShape.Cloud, 49.844560040626675, 27.54736140534282, 6, 0),
            Vector("Ünïcødé 1", BlobatarShape.Droplet, 50.359663801500574, 24.32020957207773, 0, 1),
            Vector("Ünïcødé 0", BlobatarShape.Hexagon, 50.91599252889864, 38.756451117200776, 0, 0),
            Vector("caf\u00e9-0", BlobatarShape.Nub, 51.211489111417904, 33.300000962503255, 2, 0),
            Vector("user-0", BlobatarShape.Organic, 49.612084234599024, 33.30867055311333, 0, 0),
            Vector("caf\u00e9-1", BlobatarShape.Round, 49.369840032886714, 34.37162763928063, 0, 0),
            Vector("0", BlobatarShape.Sun, 50.847750873770565, 23.70633156476542, 6, 0),
            Vector("caf\u00e9-14", BlobatarShape.Triangle, 50.49403013358824, 36.73954138085246, 0, 0),
        )
        for (vector in vectors) {
            val layout = resolveBlobatar(vector.seed).layout
            assertEquals(vector.shape, layout.shape)
            assertClose(vector.cx, layout.body.cx, "${vector.seed} body.cx")
            assertClose(vector.rx, layout.body.rx, "${vector.seed} body.rx")
            assertEquals(vector.petals, layout.petals.size)
            assertEquals(vector.extras, layout.extras.size)
            assertEquals(2, layout.eyes.size)
        }
    }

    @Test
    fun `organic body face and eye geometry match reference vector`() {
        val layout = resolveBlobatar("user-0").layout
        assertClose(49.67805197229609, layout.body.cy, "body.cy")
        assertClose(30.77120981474795, layout.body.ry, "body.ry")
        assertClose(2.4397337595466526, layout.body.n, "body.n")
        assertEquals(8, layout.body.radii.size)
        assertClose(0.9785187020152807, layout.body.radii[0], "body.radii[0]")
        assertClose(27.239859660137412, layout.face.rx, "face.rx")
        assertClose(25.16472206808119, layout.face.ry, "face.ry")
        assertClose(39.44208393787112, layout.eyes[0].cx, "eyes[0].cx")
        assertClose(6.123835304713464, layout.eyes[0].ry, "eyes[0].ry")
        assertClose(4.862419137964025, layout.eyes[1].rotation, "eyes[1].rot")
    }

    @Test
    fun `backdrop variants resolve to independent 100-unit paths`() {
        assertEquals(null, blobatarBackdropPath(BlobatarBackdrop.None))
        assertEquals(5, blobatarBackdropPath(BlobatarBackdrop.Square)?.commands?.size)
        assertEquals(6, blobatarBackdropPath(BlobatarBackdrop.Circle)?.commands?.size)
        assertEquals(6, blobatarBackdropPath(BlobatarBackdrop.Squircle)?.commands?.size)
    }

    private fun assertClose(expected: Double, actual: Double, reason: String) {
        val scale = max(abs(expected), abs(actual))
        assertTrue("$reason expected ~$expected, got $actual", abs(expected - actual) <= scale * 1e-9)
    }

    private data class Vector(
        val seed: String,
        val shape: BlobatarShape,
        val cx: Double,
        val rx: Double,
        val petals: Int,
        val extras: Int,
    )
}
