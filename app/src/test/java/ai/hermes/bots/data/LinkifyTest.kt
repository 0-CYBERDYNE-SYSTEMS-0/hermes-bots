package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Agent-ux P0 (spec §1): link boundary rules the renderer's scanner relies on. */
class LinkifyTest {

    @Test
    fun `markdown link matches only at the bracket`() {
        val text = "see [the docs](https://example.com/a) now"
        val at = Linkify.markdownLinkAt(text, 4) ?: error("expected a match")
        assertEquals("the docs", at.label)
        assertEquals("https://example.com/a", at.url)
        assertEquals(37, at.endExclusive)

        assertNull(Linkify.markdownLinkAt(text, 0))
        assertNull(Linkify.markdownLinkAt("[no url]( )", 0))
        assertNull(Linkify.markdownLinkAt("[unbalanced](https://x", 0))
    }

    @Test
    fun `bare url keeps balanced parens`() {
        val text = "https://en.wikipedia.org/wiki/Foo_(bar) is nice"
        val end = Linkify.bareUrlEnd(text, 0)
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", text.substring(0, end))
    }

    @Test
    fun `bare url drops sentence punctuation`() {
        val text = "visit https://example.com."
        assertEquals("https://example.com", text.substring(6, Linkify.bareUrlEnd(text, 6)))
    }

    @Test
    fun `bare url drops an unbalanced closing paren`() {
        val text = "(see https://example.com/a)"
        val end = Linkify.bareUrlEnd(text, 5)
        assertEquals("https://example.com/a", text.substring(5, end))
    }

    @Test
    fun `angle bracket stops the scan`() {
        val text = "https://example.com/a>"
        assertEquals("https://example.com/a", text.substring(0, Linkify.bareUrlEnd(text, 0)))
    }

    @Test
    fun `scheme without authority is not a link`() {
        assertEquals(0, Linkify.bareUrlEnd("https://", 0))
        assertEquals(0, Linkify.bareUrlEnd("nope", 0))
    }
}
