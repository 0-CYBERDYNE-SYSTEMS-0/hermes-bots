package ai.hermes.bots.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Lite markdown: fenced code blocks, inline `code`, **bold**, *italic*,
 * http(s) links, "- " bullets, and #-headings as bold. Enough for chat output;
 * no external dependency.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        val segments = splitFences(text)
        segments.forEach { segment ->
            if (segment is Segment.Code) {
                Text(
                    segment.body,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(10.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            } else {
                Text(
                    annotate((segment as Segment.Text).body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private sealed interface Segment {
    data class Text(val body: String) : Segment
    data class Code(val body: String) : Segment
}

private fun splitFences(text: String): List<Segment> {
    val segments = mutableListOf<Segment>()
    val buffer = StringBuilder()
    var inCode = false
    val codeBody = StringBuilder()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                segments += Segment.Text(buffer.toString())
                buffer.clear()
                segments += Segment.Code(codeBody.toString().removeSuffix("\n"))
                codeBody.clear()
                inCode = false
            } else {
                segments += Segment.Text(buffer.toString())
                buffer.clear()
                inCode = true
            }
        } else if (inCode) {
            codeBody.appendLine(line)
        } else {
            buffer.appendLine(line)
        }
    }
    if (inCode && codeBody.isNotEmpty()) segments += Segment.Code(codeBody.toString().removeSuffix("\n"))
    if (buffer.isNotEmpty()) segments += Segment.Text(buffer.toString())
    return segments.filter {
        (it is Segment.Text && it.body.isNotBlank()) || it is Segment.Code
    }
}

private fun annotate(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    lines.forEachIndexed { lineIdx, line ->
        if (lineIdx > 0) append("\n")
        var rest = line
        // bullet
        val bullet = rest.trimStart().startsWith("- ") || rest.trimStart().startsWith("* ")
        var heading = false
        var work = rest
        for (h in 1..4) {
            if (work.startsWith("#".repeat(h) + " ")) { work = work.substring(h + 1); heading = true }
        }
        if (bullet) work = work.trimStart().substring(2)
        if (heading) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(work) }
            return@forEachIndexed
        }
        var cursor = 0
        var pos = 0
        while (pos < work.length) {
            when {
                work.startsWith("**", pos) -> {
                    val end = work.indexOf("**", pos + 2)
                    if (end > pos + 1) {
                        append(work.substring(cursor, pos))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(work.substring(pos + 2, end)) }
                        cursor = end + 2
                        pos = cursor
                        continue
                    }
                }
                work[pos] == '`' -> {
                    val end = work.indexOf('`', pos + 1)
                    if (end > pos) {
                        append(work.substring(cursor, pos))
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(work.substring(pos + 1, end)) }
                        cursor = end + 1
                        pos = cursor
                        continue
                    }
                }
            }
            pos++
        }
        if (cursor < work.length) append(work.substring(cursor))
    }
}
