package com.dsh.android.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Minimal Markdown renderer for assistant output (no external dependency):
 * headings, bold/italic, inline code, fenced code blocks, quotes,
 * unordered/ordered lists. Unknown syntax falls back to plain text.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val typography = MaterialTheme.typography
    val annotated = remember(text, typography) { renderMarkdown(text, typography) }
    SelectionContainer {
        Text(annotated, modifier = modifier)
    }
}

private fun renderMarkdown(text: String, typography: Typography): AnnotatedString {
    val body = typography.bodyMedium
    val mono = FontFamily.Monospace
    return buildAnnotatedString {
        val lines = text.split('\n')
        var i = 0
        var inCodeFence = false
        var codeLang = ""
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    if (!inCodeFence) codeLang = trimmed.removePrefix("```").trim()
                    inCodeFence = !inCodeFence
                    i++
                }

                inCodeFence -> {
                    appendCode(line, mono)
                    i++
                }

                trimmed.startsWith("#### ") -> { appendHeading(trimmed.removePrefix("#### "), body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)); i++ }
                trimmed.startsWith("### ") -> { appendHeading(trimmed.removePrefix("### "), body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)); i++ }
                trimmed.startsWith("## ") -> { appendHeading(trimmed.removePrefix("## "), body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)); i++ }
                trimmed.startsWith("# ") -> { appendHeading(trimmed.removePrefix("# "), body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)); i++ }

                trimmed.startsWith("> ") -> {
                    appendInline(trimmed.removePrefix("> "), body.copy(fontStyle = FontStyle.Italic))
                    i++
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    append("•  ")
                    appendInline(trimmed.substring(2), body)
                    i++
                }

                Regex("^\\d+[.)] ").containsMatchIn(trimmed) -> {
                    val marker = Regex("^\\d+[.)] ").find(trimmed)!!.value
                    append("$marker")
                    appendInline(trimmed.removePrefix(marker), body)
                    i++
                }

                line.isBlank() -> {
                    append('\n')
                    i++
                }

                else -> {
                    appendInline(line, body)
                    i++
                }
            }
            if (i < lines.size && !inCodeFence) append('\n')
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendCode(line: String, mono: FontFamily) {
    withStyle(SpanStyle(fontFamily = mono, fontSize = 13.sp)) {
        append(line)
    }
    append('\n')
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendHeading(text: String, style: TextStyle) {
    withStyle(SpanStyle(fontWeight = style.fontWeight ?: FontWeight.Bold, fontSize = style.fontSize ?: 16.sp)) {
        appendInline(text, style)
    }
    append('\n')
}

private val INLINE = Regex("""(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`|\[[^\]]+]\([^)]*\))""")

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(text: String, base: TextStyle) {
    val parts = INLINE.split(text)
    for (part in parts) {
        when {
            part.startsWith("**") && part.endsWith("**") && part.length > 4 -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.substring(2, part.length - 2)) }
            }

            part.startsWith("*") && part.endsWith("*") && part.length > 2 -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.substring(1, part.length - 1)) }
            }

            part.startsWith("`") && part.endsWith("`") && part.length > 2 -> {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = (base.fontSize ?: 14.sp) * 0.92f)) {
                    append(part.substring(1, part.length - 1))
                }
            }

            part.startsWith("[") && part.contains("](") && part.endsWith(")") -> {
                val label = part.substring(1, part.indexOf("]("))
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(label) }
            }

            else -> append(part)
        }
    }
}
