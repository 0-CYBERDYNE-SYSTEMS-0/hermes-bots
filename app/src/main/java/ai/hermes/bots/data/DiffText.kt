package ai.hermes.bots.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One classified row of a unified diff (agent-ux-p0-spec.md §4). */
data class DiffLine(val kind: DiffLineKind, val text: String)

enum class DiffLineKind { FILE, META, HUNK, ADD, DEL, CONTEXT }

/**
 * `tool.complete` carries `inline_diff?` (PROTOCOL.md §6) but the wire shape is not pinned
 * beyond that, so extraction is tolerant: a JSON string is the diff text; an object yields
 * the first non-blank of `diff`/`patch`/`text`; an array joins its extracted elements.
 * Anything else (including null) means the tool simply has no diff to show.
 */
object DiffText {

    /** Parse cap — diffs beyond this render truncated inside the chip. */
    const val MAX_LINES = 400

    fun extract(payload: JsonElement?): String? = when (payload) {
        null, is JsonNull -> null
        is JsonPrimitive -> payload.content.takeIf { it.isNotBlank() }
        is JsonObject -> payload.entries.asSequence()
            .filter { it.key in DIFF_KEYS }
            .mapNotNull { extract(it.value) }
            .firstOrNull()
        is JsonArray -> payload.asSequence()
            .mapNotNull { extract(it) }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { null }
        else -> null
    }

    /** Classify lines and cap the result; [ParseResult.truncated] reports the cap bit. */
    fun parse(raw: String, maxLines: Int = MAX_LINES): ParseResult {
        val all = splitBody(raw)
        val lines = all.take(maxLines).map { line ->
            val kind = when {
                line.startsWith("@@") -> DiffLineKind.HUNK
                line.startsWith("+++") || line.startsWith("---") -> DiffLineKind.FILE
                line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("rename ") || line.startsWith("new file") ||
                    line.startsWith("deleted file") || line.startsWith("old mode") ||
                    line.startsWith("new mode") || line.startsWith("similarity ") ->
                    DiffLineKind.META
                line.startsWith("+") -> DiffLineKind.ADD
                line.startsWith("-") -> DiffLineKind.DEL
                else -> DiffLineKind.CONTEXT
            }
            DiffLine(kind, line)
        }
        return ParseResult(lines, truncated = all.size > maxLines)
    }

    /** A trailing newline terminates the last line — it doesn't add an empty one. */
    private fun splitBody(raw: String): List<String> =
        if (raw.endsWith("\n")) raw.lines().dropLast(1) else raw.lines()

    private val DIFF_KEYS = setOf("diff", "patch", "text")

    data class ParseResult(val lines: List<DiffLine>, val truncated: Boolean)
}
