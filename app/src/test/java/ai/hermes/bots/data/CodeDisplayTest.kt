package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent-ux P0 (spec §2–3): fence language aliases and the lightweight code tokenizer. */
class CodeDisplayTest {

    // ---------- CodeLanguages ----------

    @Test
    fun `fence aliases map to display names`() {
        assertEquals("Kotlin", CodeLanguages.label("kotlin"))
        assertEquals("Kotlin", CodeLanguages.label("kt"))
        assertEquals("Python", CodeLanguages.label("py"))
        assertEquals("Bash", CodeLanguages.label("sh"))
        assertEquals("TypeScript", CodeLanguages.label("ts"))
        assertEquals("C++", CodeLanguages.label("cpp"))
        assertEquals("YAML", CodeLanguages.label("yml"))
        assertEquals("Rust", CodeLanguages.label("rs"))
    }

    @Test
    fun `fence info with options keeps the language token`() {
        assertEquals("Python", CodeLanguages.label("python title=x"))
        assertEquals("JSON", CodeLanguages.label(" json "))
    }

    @Test
    fun `unknown or empty fence info yields no chip`() {
        assertNull(CodeLanguages.label("weirdlang"))
        assertNull(CodeLanguages.label(""))
        assertNull(CodeLanguages.label("   "))
    }

    // ---------- CodeTokenizer ----------

    @Test
    fun `kotlin code yields keywords comments and numbers`() {
        val code = "fun main() {\n  val x = 42 // count\n}"
        val spans = CodeTokenizer.tokenize(code, "kotlin")
        fun texts(kind: CodeTokenKind) =
            spans.filter { it.kind == kind }.map { code.substring(it.start, it.end) }
        assertTrue("fun" in texts(CodeTokenKind.KEYWORD))
        assertTrue("val" in texts(CodeTokenKind.KEYWORD))
        assertTrue("42" in texts(CodeTokenKind.NUMBER))
        assertEquals(listOf("// count"), texts(CodeTokenKind.COMMENT))
    }

    @Test
    fun `escaped quote does not end the string`() {
        val q = "\""
        val code = "val s = ${q}a \\\" b${q}"
        val spans = CodeTokenizer.tokenize(code, "kotlin")
        val strings = spans.filter { it.kind == CodeTokenKind.STRING }
        assertEquals(1, strings.size)
        assertEquals(code.length, strings[0].end)
    }

    @Test
    fun `block comments span lines`() {
        val code = "val a = 1\n/* one\ntwo */\nval b = 2"
        val spans = CodeTokenizer.tokenize(code, "kotlin")
        val comments = spans.filter { it.kind == CodeTokenKind.COMMENT }
        assertEquals(1, comments.size)
        assertEquals("/* one\ntwo */", code.substring(comments[0].start, comments[0].end))
    }

    @Test
    fun `python uses hash comments`() {
        val code = "# note\nx = 1"
        val spans = CodeTokenizer.tokenize(code, "python")
        val comments = spans.filter { it.kind == CodeTokenKind.COMMENT }
        assertEquals(1, comments.size)
        assertEquals("# note", code.substring(comments[0].start, comments[0].end))
    }

    @Test
    fun `sql uses dash-dash comments and keywords`() {
        val code = "SELECT 1 -- count"
        val spans = CodeTokenizer.tokenize(code, "sql")
        fun texts(kind: CodeTokenKind) =
            spans.filter { it.kind == kind }.map { code.substring(it.start, it.end) }
        assertTrue("select" in texts(CodeTokenKind.KEYWORD).map { it.lowercase() })
        assertTrue("1" in texts(CodeTokenKind.NUMBER))
        assertEquals(listOf("-- count"), texts(CodeTokenKind.COMMENT))
    }

    @Test
    fun `json gets strings and numbers but no keywords`() {
        val code = "{\n  \"a\": 1\n}"
        val spans = CodeTokenizer.tokenize(code, "json")
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.none { it.kind == CodeTokenKind.KEYWORD })
        assertTrue(spans.any { it.kind == CodeTokenKind.STRING })
        assertTrue(spans.any { it.kind == CodeTokenKind.NUMBER })
    }

    @Test
    fun `decimal numbers stay one span`() {
        val code = "x = 3.14"
        val spans = CodeTokenizer.tokenize(code, "python")
        val numbers = spans.filter { it.kind == CodeTokenKind.NUMBER }
        assertEquals(listOf("3.14"), numbers.map { code.substring(it.start, it.end) })
    }

    @Test
    fun `unknown language still highlights strings`() {
        val code = "weird \"literal\" tail"
        val spans = CodeTokenizer.tokenize(code, "weirdlang")
        val strings = spans.filter { it.kind == CodeTokenKind.STRING }
        assertEquals(1, strings.size)
        assertEquals("\"literal\"", code.substring(strings[0].start, strings[0].end))
    }

    @Test
    fun `oversized bodies are not tokenized`() {
        val code = "a".repeat(CodeTokenizer.MAX_TOKENIZE_CHARS + 1)
        assertTrue(CodeTokenizer.tokenize(code, "kotlin").isEmpty())
    }

    @Test
    fun `empty body yields no spans`() {
        assertTrue(CodeTokenizer.tokenize("", "kotlin").isEmpty())
    }
}
