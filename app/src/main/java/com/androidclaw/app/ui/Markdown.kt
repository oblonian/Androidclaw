package com.androidclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A tiny, dependency-free Markdown renderer — enough for chat replies: headings,
 * bold / italic / inline code, bullet and numbered lists, and fenced code blocks.
 * Anything it doesn't recognise is shown as plain text, so it degrades gracefully.
 */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val marker: String, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Blank : MdBlock
}

private val numberedRegex = Regex("""^(\d+)\.\s+(.*)""")

private fun parseBlocks(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = src.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                if (i < lines.size) i++ // consume closing fence
                blocks += MdBlock.Code(code.toString().trimEnd('\n'))
            }
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(3)
                blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                blocks += MdBlock.Bullet(trimmed.drop(2).trim())
                i++
            }
            numberedRegex.matches(trimmed) -> {
                val m = numberedRegex.find(trimmed)!!
                blocks += MdBlock.Numbered(m.groupValues[1] + ".", m.groupValues[2].trim())
                i++
            }
            trimmed.isEmpty() -> {
                blocks += MdBlock.Blank
                i++
            }
            else -> {
                blocks += MdBlock.Paragraph(line)
                i++
            }
        }
    }
    return blocks
}

/** Parses inline spans (**bold**, *italic*, `code`) into a styled string. */
private fun AnnotatedString.Builder.appendInline(text: String, codeBg: Color) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(text.substring(i + 2, end), codeBg)
                    }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            (text[i] == '*' || text[i] == '_') -> {
                val ch = text[i]
                val end = text.indexOf(ch, i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(text.substring(i + 1, end), codeBg)
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
private fun inlineSpans(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, codeBg) {
        buildAnnotatedString { appendInline(text, codeBg) }
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineSpans(block.text),
                    color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                is MdBlock.Paragraph -> Text(
                    inlineSpans(block.text),
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = 2.dp)) {
                    Text("•  ", color = color, style = MaterialTheme.typography.bodyMedium)
                    Text(inlineSpans(block.text), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Numbered -> Row(Modifier.padding(start = 2.dp)) {
                    Text("${block.marker}  ", color = color, style = MaterialTheme.typography.bodyMedium)
                    Text(inlineSpans(block.text), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Code -> Text(
                    text = block.text,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(8.dp),
                        )
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                MdBlock.Blank -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}
