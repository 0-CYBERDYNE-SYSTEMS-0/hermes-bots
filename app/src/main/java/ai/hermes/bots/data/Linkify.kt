package ai.hermes.bots.data

/**
 * Pure URL-scanning for the markdown renderer (agent-ux-p0-spec.md §1): where a link starts,
 * where it ends. The renderer's scanner decides *when* to ask (never inside code spans);
 * this object owns the boundary rules — trailing sentence punctuation isn't part of a URL,
 * a closing paren the URL never opened belongs to the sentence, balanced parens are kept.
 */
object Linkify {

    data class Match(val label: String, val url: String, val endExclusive: Int)

    /** A `[label](url)` match starting exactly at [pos], else null. */
    fun markdownLinkAt(text: String, pos: Int): Match? {
        if (pos >= text.length || text[pos] != '[') return null
        val m = markdownLink.matchAt(text, pos) ?: return null
        return Match(m.groupValues[1], m.groupValues[2], pos + m.value.length)
    }

    /** Exclusive end of a bare http(s) URL starting at [start]; [start] itself when none. */
    fun bareUrlEnd(text: String, start: Int): Int {
        if (!(text.startsWith("http://", start) || text.startsWith("https://", start))) return start
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>") end++
        while (end > start && text[end - 1] in tailTrim) end--
        if (end > start && text[end - 1] == ')' && text.indexOf('(', start) < 0) end--
        return if (end > start + "https://".length) end else start
    }

    private val markdownLink = Regex("\\[([^\\]\n]+)]\\(([^()\\s]+)\\)")
    private val tailTrim = charArrayOf('.', ',', ';', ':', '!', '?', '"', '\'', '>')
}
