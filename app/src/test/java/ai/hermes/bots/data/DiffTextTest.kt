package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent-ux P0 (spec §4): tolerant `inline_diff` extraction and unified-diff classification. */
class DiffTextTest {

    private val sample = """
        diff --git a/f.kt b/f.kt
        index 123..456 100644
        --- a/f.kt
        +++ b/f.kt
        @@ -1,3 +1,3 @@
         context
        -removed
        +added
    """.trimIndent()

    @Test
    fun `unified diff lines classify`() {
        val result = DiffText.parse(sample)
        assertEquals(
            listOf(
                DiffLineKind.META,
                DiffLineKind.META,
                DiffLineKind.FILE,
                DiffLineKind.FILE,
                DiffLineKind.HUNK,
                DiffLineKind.CONTEXT,
                DiffLineKind.DEL,
                DiffLineKind.ADD,
            ),
            result.lines.map { it.kind },
        )
        assertFalse(result.truncated)
    }

    @Test
    fun `trailing newline does not create a phantom context line`() {
        val result = DiffText.parse("$sample\n")
        assertEquals(DiffLineKind.ADD, result.lines.last().kind)
        assertEquals(sample.lines().size, result.lines.size)
    }

    @Test
    fun `cap truncates and reports it`() {
        val result = DiffText.parse(sample, maxLines = 3)
        assertEquals(3, result.lines.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `extract reads string payloads`() {
        assertEquals(sample, DiffText.extract(Json.parseToJsonElement("\"$sample\"")))
    }

    @Test
    fun `extract reads object payloads`() {
        assertEquals(
            "the diff",
            DiffText.extract(Json.parseToJsonElement("""{"diff": "the diff"}""")),
        )
        assertEquals(
            "the patch",
            DiffText.extract(Json.parseToJsonElement("""{"patch": "the patch"}""")),
        )
    }

    @Test
    fun `extract joins array payloads`() {
        assertEquals(
            "a\n\nb",
            DiffText.extract(Json.parseToJsonElement("""[{"diff": "a"}, {"diff": "b"}]""")),
        )
    }

    @Test
    fun `extract tolerates absent or junk payloads`() {
        assertNull(DiffText.extract(null))
        assertNull(DiffText.extract(Json.parseToJsonElement("null")))
        assertNull(DiffText.extract(Json.parseToJsonElement("""{"unrelated": 1}""")))
        assertNull(DiffText.extract(Json.parseToJsonElement("[]")))
    }
}
