package ai.hermes.bots.ui.chat

import ai.hermes.bots.data.CodeLanguages
import ai.hermes.bots.data.CodeTokenKind
import ai.hermes.bots.data.CodeTokenizer
import ai.hermes.bots.data.Linkify
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp

/**
 * Lite markdown: fenced code blocks (language chip, copy button, lightweight highlighting),
 * inline `code`, **bold**, "- " bullets, and #-headings as bold; [label](url) links and bare
 * URLs are tappable (agent-ux-p0-spec.md §1–3). Enough for chat output; no external
 * dependency. Links open through the system handler — no WebView, no Custom Tabs dependency.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier) {
        val segments = splitFences(text)
        segments.forEach { segment ->
            if (segment is Segment.Code) {
                CodeBlock(segment)
            } else {
                val linkStyle = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )
                Text(
                    annotate((segment as Segment.Text).body, linkStyle) { url ->
                        // No browser installed must never crash a chat tap.
                        runCatching { uriHandler.openUri(url) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(segment: Segment.Code) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var copied by remember(segment.body) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    val langLabel = CodeLanguages.label(segment.info)
    val colors = MaterialTheme.colorScheme
    val highlighted = remember(segment.body, segment.info, colors) {
        buildAnnotatedString {
            append(segment.body)
            for (span in CodeTokenizer.tokenize(segment.body, segment.info)) {
                val style = when (span.kind) {
                    CodeTokenKind.KEYWORD -> SpanStyle(color = colors.primary)
                    CodeTokenKind.STRING -> SpanStyle(color = colors.tertiary)
                    CodeTokenKind.COMMENT -> SpanStyle(
                        color = colors.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                    CodeTokenKind.NUMBER -> SpanStyle(color = colors.secondary)
                }
                addStyle(style, span.start, span.end)
            }
        }
    }
    val scrollState = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (langLabel != null) {
                Text(
                    langLabel.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            // Text action, not an icon: the core icon set has no copy glyph and the repo
            // stays free of the extended-icons dependency (same idiom as "Show all").
            Text(
                if (copied) "Copied" else "Copy",
                style = MaterialTheme.typography.labelMedium,
                color = if (copied) colors.onSurfaceVariant else colors.primary,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clickable {
                        clipboard.setText(AnnotatedString(segment.body))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        copied = true
                    },
            )
        }
        Text(
            highlighted,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                .horizontalScroll(scrollState),
        )
    }
}

private sealed interface Segment {
    data class Text(val body: String) : Segment
    data class Code(val info: String, val body: String) : Segment
}

private fun splitFences(text: String): List<Segment> {
    val segments = mutableListOf<Segment>()
    val buffer = StringBuilder()
    var inCode = false
    var fenceInfo = ""
    val codeBody = StringBuilder()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                segments += Segment.Text(buffer.toString())
                buffer.clear()
                segments += Segment.Code(fenceInfo, codeBody.toString().removeSuffix("\n"))
                codeBody.clear()
                inCode = false
            } else {
                segments += Segment.Text(buffer.toString())
                buffer.clear()
                // "```kotlin" / "``` kotlin" — the language token the old renderer discarded.
                fenceInfo = trimmed.removePrefix("```").trim()
                inCode = true
            }
        } else if (inCode) {
            codeBody.appendLine(line)
        } else {
            buffer.appendLine(line)
        }
    }
    if (inCode && codeBody.isNotEmpty()) segments += Segment.Code(fenceInfo, codeBody.toString().removeSuffix("\n"))
    if (buffer.isNotEmpty()) segments += Segment.Text(buffer.toString())
    return segments.filter {
        (it is Segment.Text && it.body.isNotBlank()) || it is Segment.Code
    }
}

private fun annotate(
    text: String,
    linkStyle: SpanStyle,
    openUrl: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    val linkListener = LinkInteractionListener { link ->
        (link as? LinkAnnotation.Url)?.url?.let(openUrl)
    }
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
        if (bullet) {
            // Emit a glyph for the stripped marker (SV-19); keep any indent the line carried.
            val indent = work.takeWhile { it == ' ' || it == '\t' }
            work = "$indent•  ${work.trimStart().substring(2)}"
        }
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
                work[pos] == '[' -> {
                    val link = Linkify.markdownLinkAt(work, pos)
                    if (link != null) {
                        append(work.substring(cursor, pos))
                        withLink(
                            LinkAnnotation.Url(link.url, TextLinkStyles(linkStyle), linkListener),
                        ) { append(link.label) }
                        cursor = link.endExclusive
                        pos = cursor
                        continue
                    }
                }
                work.startsWith("http://", pos) || work.startsWith("https://", pos) -> {
                    val end = Linkify.bareUrlEnd(work, pos)
                    if (end > pos) {
                        append(work.substring(cursor, pos))
                        val url = work.substring(pos, end)
                        withLink(
                            LinkAnnotation.Url(url, TextLinkStyles(linkStyle), linkListener),
                        ) { append(url) }
                        cursor = end
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
