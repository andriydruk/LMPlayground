package com.druk.lmplayground.conversation

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.druk.lmplayground.R
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.Block
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

val thinkPattern by lazy {
    Regex("""<think>(.*?)</think>\n?|<think>(.*)""", RegexOption.DOT_MATCHES_ALL)
}

enum class SymbolAnnotationType {
    PERSON, LINK
}

typealias StringAnnotation = AnnotatedString.Range<String>
typealias SymbolAnnotation = Pair<AnnotatedString, StringAnnotation?>

private val markdownParser by lazy {
    Parser.builder()
        .extensions(listOf(StrikethroughExtension.create()))
        .build()
}

fun stripThinkTags(text: String): String {
    return thinkPattern.replace(text, "").trim()
}

data class ThinkingSplit(
    val thinkingContent: String,
    val responseContent: String
)

fun splitThinking(text: String): ThinkingSplit {
    val matches = thinkPattern.findAll(text).toList()
    if (matches.isEmpty()) return ThinkingSplit("", text)

    val thinking = StringBuilder()
    var cursor = 0
    for (match in matches) {
        val content = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
        if (content.isNotEmpty()) {
            if (thinking.isNotEmpty()) thinking.append("\n")
            thinking.append(content)
        }
        cursor = match.range.last + 1
    }
    val response = if (cursor < text.length) text.substring(cursor).trimStart() else ""
    return ThinkingSplit(thinking.toString(), response)
}

/**
 * Format a message by parsing Markdown into a styled AnnotatedString.
 *
 * Handles bold, italic, inline code, code blocks, headings, lists, links,
 * blockquotes, strikethrough, horizontal rules, and <think> blocks.
 */
@Composable
fun messageFormatter(
    text: String,
    primary: Boolean
): AnnotatedString {
    val colorScheme = MaterialTheme.colorScheme
    val thinkColor = colorScheme.outline
    val codeBackground = if (primary) colorScheme.secondary else colorScheme.surface

    val thoughtsLabel = stringResource(R.string.thoughts)

    return buildAnnotatedString {
        val thinkMatches = thinkPattern.findAll(text).toList()

        if (thinkMatches.isEmpty()) {
            appendMarkdown(text, colorScheme, primary, codeBackground)
            return@buildAnnotatedString
        }

        var cursor = 0
        for (match in thinkMatches) {
            if (cursor < match.range.first) {
                appendMarkdown(
                    text.substring(cursor, match.range.first),
                    colorScheme, primary, codeBackground
                )
            }

            val thinkContent = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            if (thinkContent.isNotEmpty()) {
                pushStyle(SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = thinkColor
                ))
                append(thoughtsLabel)
                pop()
                append("\n")
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = thinkColor))
                append(thinkContent)
                pop()
            }

            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            val remainingText = text.substring(cursor).trimStart()
            if (remainingText.isNotEmpty()) {
                append("\n\n")
                appendMarkdown(remainingText, colorScheme, primary, codeBackground)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdown(
    text: String,
    colorScheme: ColorScheme,
    primary: Boolean,
    codeBackground: Color
) {
    if (text.isEmpty()) return
    val document = markdownParser.parse(text)
    val ctx = RenderContext(colorScheme, primary, codeBackground)
    renderNode(document, ctx, isFirstBlock = true)
}

private data class RenderContext(
    val colorScheme: ColorScheme,
    val primary: Boolean,
    val codeBackground: Color
)

private val headingSizes = mapOf(
    1 to 24.sp,
    2 to 20.sp,
    3 to 18.sp,
    4 to 16.sp,
    5 to 14.sp,
    6 to 13.sp
)

private fun AnnotatedString.Builder.renderNode(
    node: Node,
    ctx: RenderContext,
    isFirstBlock: Boolean = false,
    listDepth: Int = 0,
    orderedIndex: Int? = null
) {
    when (node) {
        is Document -> renderChildren(node, ctx, listDepth = listDepth)

        is Heading -> {
            if (!isFirstBlock) append("\n\n")
            val fontSize = headingSizes[node.level] ?: 14.sp
            pushStyle(SpanStyle(fontSize = fontSize, fontWeight = FontWeight.Bold))
            renderChildren(node, ctx, listDepth = listDepth)
            pop()
        }

        is Paragraph -> {
            val insideListItem = node.parent is ListItem
            if (!isFirstBlock && !insideListItem) append("\n\n")
            renderChildren(node, ctx, listDepth = listDepth)
        }

        is BlockQuote -> {
            if (!isFirstBlock) append("\n\n")
            pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = ctx.colorScheme.outline))
            append("\u2502 ")
            renderChildren(node, ctx, listDepth = listDepth)
            pop()
        }

        is BulletList -> {
            if (!isFirstBlock) {
                append(if (node.parent is ListItem) "\n" else "\n\n")
            }
            var child = node.firstChild
            var isFirst = true
            while (child != null) {
                renderNode(child, ctx, isFirstBlock = isFirst, listDepth = listDepth + 1)
                isFirst = false
                child = child.next
            }
        }

        is OrderedList -> {
            if (!isFirstBlock) {
                append(if (node.parent is ListItem) "\n" else "\n\n")
            }
            var child = node.firstChild
            var index = node.markerStartNumber ?: 1
            var isFirst = true
            while (child != null) {
                renderNode(
                    child, ctx,
                    isFirstBlock = isFirst,
                    listDepth = listDepth + 1,
                    orderedIndex = index
                )
                index++
                isFirst = false
                child = child.next
            }
        }

        is ListItem -> {
            if (!isFirstBlock) append("\n")
            val indent = "  ".repeat((listDepth - 1).coerceAtLeast(0))
            val bullet = if (orderedIndex != null) "$indent$orderedIndex. " else "$indent\u2022 "
            append(bullet)
            renderChildren(node, ctx, listDepth = listDepth)
        }

        is FencedCodeBlock -> {
            if (!isFirstBlock) append("\n\n")
            pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = ctx.codeBackground
                )
            )
            append(node.literal.trimEnd('\n'))
            pop()
        }

        is IndentedCodeBlock -> {
            if (!isFirstBlock) append("\n\n")
            pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = ctx.codeBackground
                )
            )
            append(node.literal.trimEnd('\n'))
            pop()
        }

        is ThematicBreak -> {
            if (!isFirstBlock) append("\n\n")
            pushStyle(SpanStyle(color = ctx.colorScheme.outline))
            append("\u2E3B")
            pop()
        }

        is StrongEmphasis -> {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            renderChildren(node, ctx, listDepth = listDepth)
            pop()
        }

        is Emphasis -> {
            pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            renderChildren(node, ctx, listDepth = listDepth)
            pop()
        }

        is Code -> {
            pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = ctx.codeBackground
                )
            )
            append(node.literal)
            pop()
        }

        is Link -> {
            val linkColor = if (ctx.primary) {
                ctx.colorScheme.inversePrimary
            } else {
                ctx.colorScheme.primary
            }
            pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            val startIdx = length
            renderChildren(node, ctx, listDepth = listDepth)
            val endIdx = length
            addStringAnnotation(
                tag = SymbolAnnotationType.LINK.name,
                annotation = node.destination,
                start = startIdx,
                end = endIdx
            )
            pop()
        }

        is Image -> {
            renderChildren(node, ctx, listDepth = listDepth)
        }

        is Strikethrough -> {
            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
            renderChildren(node, ctx, listDepth = listDepth)
            pop()
        }

        is HardLineBreak -> append("\n")
        is SoftLineBreak -> append("\n")
        is Text -> append(node.literal)

        is HtmlBlock -> append(node.literal.trim())
        is HtmlInline -> append(node.literal)

        else -> renderChildren(node, ctx, listDepth = listDepth)
    }
}

private fun AnnotatedString.Builder.renderChildren(
    parent: Node,
    ctx: RenderContext,
    listDepth: Int = 0
) {
    var child = parent.firstChild
    var firstBlock = true
    while (child != null) {
        renderNode(child, ctx, isFirstBlock = firstBlock, listDepth = listDepth)
        if (child is Block) firstBlock = false
        child = child.next
    }
}
