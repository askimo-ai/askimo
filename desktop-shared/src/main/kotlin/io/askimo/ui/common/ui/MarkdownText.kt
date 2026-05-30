/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.askimo.core.executable.RunnableLanguage
import io.askimo.core.logging.currentFileLogger
import io.askimo.core.util.JsonUtils.json
import io.askimo.tools.chart.MermaidChartData
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.ui.util.FileDialogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds
import org.commonmark.node.Text as MarkdownText

private val log = currentFileLogger()

/**
 * Simple Markdown renderer for Compose.
 *
 * @param onRunRequest Called when the user clicks the Run button on a code block.
 *   The host is responsible for showing the confirmation dialog outside any
 *   SelectionContainer to avoid "layouts are not part of the same hierarchy" crashes.
 * @param onLinkClick Called when any link is clicked. When null the default
 *   [LocalUriHandler] is used. Use this to intercept `file://` links and open
 *   them in the in-app viewer instead of the OS file browser.
 */
@Composable
fun markdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    viewportTopY: Float? = null,
    isStreaming: Boolean = false,
    onRunRequest: ((code: String, language: String) -> Unit)? = null,
    messageId: String? = null,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    // Re-parse only when content or streaming mode actually changes.
    val document = remember(markdown, isStreaming) {
        val parser = Parser.builder()
            .extensions(listOf(TablesExtension.create(), AutolinkExtension.create()))
            .build()
        val preparedMarkdown = if (isStreaming) {
            closeUnclosedFences(preprocessMarkdown(markdown))
        } else {
            preprocessMarkdown(markdown)
        }
        parser.parse(preparedMarkdown)
    }

    val contentColor = MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        SelectionContainer(modifier = modifier) {
            Column {
                renderNode(document, viewportTopY, isStreaming, onRunRequest, messageId, onLinkClick)
            }
        }
    }
}

/**
 * A markdown renderer that reveals content progressively — a few top-level nodes per frame —
 * instead of laying out the entire document in one blocking composition pass.
 *
 * Use this for large static documents (system prompts, long responses) where the full render
 * would cause a visible freeze. Content appears smoothly rather than all at once.
 *
 * @param chunkSize Number of top-level AST nodes revealed per tick (default 2).
 * @param tickMs    Delay between reveal ticks in milliseconds (default 8 ≈ 1 frame).
 */
@Composable
fun revealingMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    chunkSize: Int = 2,
    tickMs: Long = 8L,
    onRunRequest: ((code: String, language: String) -> Unit)? = null,
    messageId: String? = null,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    val document = remember(markdown) {
        val parser = Parser.builder()
            .extensions(listOf(TablesExtension.create(), AutolinkExtension.create()))
            .build()
        parser.parse(preprocessMarkdown(markdown))
    }

    val topNodes = remember(document) {
        buildList {
            var child = document.firstChild
            while (child != null) { add(child); child = child.next }
        }
    }

    var visibleCount by remember(markdown) { mutableIntStateOf(chunkSize) }

    LaunchedEffect(markdown) {
        while (visibleCount < topNodes.size) {
            delay(tickMs.milliseconds)
            visibleCount = (visibleCount + chunkSize).coerceAtMost(topNodes.size)
        }
    }

    val contentColor = MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        SelectionContainer(modifier = modifier) {
            Column {
                topNodes.take(visibleCount).forEach { node ->
                    renderSingleNode(node, viewportTopY = null, isStreaming = false, onRunRequest, messageId, onLinkClick)
                }
            }
        }
    }
}

/**
 * Pre-processes markdown to handle syntax the commonmark parser doesn't support natively.
 *
 * Footnotes:
 *  - Inline references `[^1]` → `[^fn:1]` (kept as a link-style label via inline code)
 *  - Definitions `[^1]: content` → `1. content` (becomes an ordered list item)
 */
private fun preprocessMarkdown(markdown: String): String {
    // 1. Inline references [^N] (not followed by : or () → (N) plain text
    //    Skip [^N](...) patterns — those are real markdown links generated by the AI.
    var processed = markdown.replace(Regex("""\[\^(\w+)](?![:(])""")) { match ->
        "(${match.groupValues[1]})"
    }

    // 2. Footnote definitions [^N]: content → N. content (ordered list)
    processed = processed.replace(Regex("""^\[\^(\w+)]:\s*(.*)$""", RegexOption.MULTILINE)) { match ->
        val id = match.groupValues[1]
        val content = match.groupValues[2]
        "$id. $content"
    }

    return processed
}

/**
 * During streaming, an unclosed fenced code block causes the commonmark parser to swallow all
 * subsequent text as raw paragraph content. This function detects the last unclosed fence and
 * closes it with a `|streaming` sentinel appended to the info string. [renderCodeBlock] then
 * renders a live code-viewer for such blocks instead of waiting for the full message.
 *
 * Follows CommonMark §4.5:
 * - An opener is a line with ≤3 leading spaces + 3+ fence chars + optional info string.
 * - A closer must use the same fence character, length ≥ opener, and NO info string.
 * - Lines inside an open block that look like fences are raw code content — never openers.
 *
 * Special streaming case: if the last line of the partial response is an incomplete opener
 * (e.g. the AI just emitted "```js" but the newline hasn't arrived yet) we treat it the same
 * as a complete opener so users see a live block immediately.
 */
private fun closeUnclosedFences(markdown: String): String {
    // Matches a complete fence line: ≤3 leading spaces, 3+ fence chars, optional info
    val fenceLineRegex = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    // Matches an *incomplete* last line that is the start of a fence (no trailing newline yet)
    // e.g. "```", "```js", "```json" — but NOT a fence closer (no info on closers)
    val incompleteFenceRegex = Regex("""^ {0,3}(`{3,}|~{3,})(\S*)$""")

    data class OpenFence(val char: Char, val len: Int, val info: String, val lineIndex: Int)

    var open: OpenFence? = null
    val lines = markdown.lines()

    // Process all lines except potentially the last one (which may be incomplete)
    val lastIndex = lines.lastIndex
    for ((index, line) in lines.withIndex()) {
        val isLastLine = index == lastIndex

        val match = fenceLineRegex.matchEntire(line) ?: run {
            // If this is the last line and looks like an incomplete opening fence, treat it as an opener
            if (isLastLine && open == null) {
                val incompleteMatch = incompleteFenceRegex.matchEntire(line)
                if (incompleteMatch != null) {
                    val marker = incompleteMatch.groupValues[1]
                    val info = incompleteMatch.groupValues[2].trim()
                    open = OpenFence(marker[0], marker.length, info, index)
                }
            }
            continue
        }

        val marker = match.groupValues[1]
        val rest = match.groupValues[2].trim()

        if (open == null) {
            open = OpenFence(marker[0], marker.length, rest, index)
        } else if (marker[0] == open.char && marker.length >= open.len && rest.isEmpty()) {
            open = null // properly closed
        }
        // else: fence-like line inside a block = code content, skip
    }

    if (open == null) return markdown

    val fenceMarker = open.char.toString().repeat(open.len)
    val updatedLines = lines.toMutableList()
    val infoStr = if (open.info.isNotEmpty()) open.info else ""
    updatedLines[open.lineIndex] = "$fenceMarker$infoStr|streaming"
    return updatedLines.joinToString("\n") + "\n$fenceMarker\n"
}

@Composable
private fun renderNode(node: Node, viewportTopY: Float? = null, isStreaming: Boolean = false, onRunRequest: ((String, String) -> Unit)? = null, messageId: String? = null, onLinkClick: ((url: String) -> Unit)? = null) {
    var child = node.firstChild
    while (child != null) {
        renderSingleNode(child, viewportTopY, isStreaming, onRunRequest, messageId, onLinkClick)
        child = child.next
    }
}

@Composable
private fun renderSingleNode(node: Node, viewportTopY: Float? = null, isStreaming: Boolean = false, onRunRequest: ((String, String) -> Unit)? = null, messageId: String? = null, onLinkClick: ((url: String) -> Unit)? = null) {
    when (node) {
        is Paragraph -> {
            val videoUrl = extractVideoUrl(node)
            if (videoUrl != null) {
                renderVideo(videoUrl)
            } else {
                renderParagraph(node, onLinkClick)
            }
        }

        is Heading -> renderHeading(node, onLinkClick)

        is BulletList -> renderBulletList(node, onLinkClick)

        is OrderedList -> renderOrderedList(node, onLinkClick)

        is FencedCodeBlock -> renderCodeBlock(
            node,
            viewportTopY,
            isStreaming,
            onRunRequest,
            messageId,
        )

        is BlockQuote -> renderBlockQuote(
            node,
            viewportTopY,
            onRunRequest,
            onLinkClick,
        )

        is TableBlock -> renderTable(node)

        is Image -> {
            val destination = node.destination
            if (isVideoUrl(destination)) {
                renderVideo(destination)
            } else {
                renderImage(node)
            }
        }

        else -> renderNode(node, viewportTopY, isStreaming, onRunRequest, messageId, onLinkClick)
    }
}

@Composable
private fun renderParagraph(paragraph: Paragraph, onLinkClick: ((url: String) -> Unit)? = null) {
    // Check if this paragraph contains only an image
    val firstChild = paragraph.firstChild
    if (firstChild is Image && firstChild.next == null) {
        // Paragraph contains only an image - render as block image
        val destination = firstChild.destination
        if (isVideoUrl(destination)) {
            renderVideo(destination)
        } else {
            renderImage(firstChild)
        }
        return
    }

    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val linkColor = MaterialTheme.colorScheme.tertiary

    // Extract raw text to check for LaTeX
    val rawText = extractTextContent(paragraph)

    // Check if this paragraph contains LaTeX formulas with \[ \] or [ ]
    val latexMatches = mutableListOf<Triple<Int, Int, String>>() // (start, end, content)

    // Find \[ ... \] or standalone [ ... ] patterns
    var i = 0
    while (i < rawText.length) {
        val hasBackslash = i < rawText.length - 1 && rawText[i] == '\\' && rawText[i + 1] == '['
        val justBracket = rawText[i] == '[' && (i == 0 || rawText[i - 1] != '\\')

        if (hasBackslash || justBracket) {
            val start = i
            val contentStart = if (hasBackslash) i + 2 else i + 1
            var j = contentStart

            while (j < rawText.length) {
                val hasEndBackslash = j < rawText.length - 1 && rawText[j] == '\\' && rawText[j + 1] == ']'
                val justEndBracket = rawText[j] == ']' && (j == 0 || rawText[j - 1] != '\\')

                if (hasEndBackslash || justEndBracket) {
                    val content = rawText.substring(contentStart, j)
                    // More comprehensive math content detection
                    val isMathContent = content.contains(Regex("[\\\\^_{}=+\\-*/]|\\b(sin|cos|tan|log|ln|exp|theta|pi|alpha|beta|gamma|delta|sum|int|frac|boxed|begin|end|aligned)\\b"))

                    if (content.isNotBlank() && isMathContent) {
                        val endIndex = if (hasEndBackslash) j + 2 else j + 1
                        // Fix markdown-mangled LaTeX content
                        val fixedContent = fixMarkdownMangledLatex(content)
                        latexMatches.add(Triple(start, endIndex, fixedContent))
                        i = endIndex
                        break
                    }
                }
                j++
            }
        }
        i++
    }

    // Find inline math \( ... \) - these should not span multiple lines
    i = 0
    while (i < rawText.length - 1) {
        if (rawText[i] == '\\' && rawText[i + 1] == '(') {
            val start = i
            val contentStart = i + 2
            var j = contentStart
            var foundEnd = false

            while (j < rawText.length - 1) {
                // Stop if we hit a newline (inline math shouldn't span lines)
                if (rawText[j] == '\n') {
                    break
                }

                if (rawText[j] == '\\' && rawText[j + 1] == ')') {
                    val content = rawText.substring(contentStart, j)
                    // Check if it's math content
                    val isMathContent = content.contains(Regex("[\\\\^_{}=+\\-*/]|\\b(sin|cos|tan|log|ln|exp|theta|pi|alpha|beta|gamma|delta|sum|int|frac)\\b"))

                    if (content.isNotBlank() && isMathContent) {
                        // Check for overlap with existing matches
                        val overlaps = latexMatches.any { (existingStart, existingEnd, _) ->
                            start in existingStart until existingEnd || existingStart in start until j + 2
                        }

                        if (!overlaps) {
                            // Fix markdown-mangled LaTeX content
                            val fixedContent =
                                fixMarkdownMangledLatex(content)
                            latexMatches.add(Triple(start, j + 2, fixedContent))
                            foundEnd = true
                        }
                        i = j + 2
                        break
                    }
                }
                j++
            }

            if (!foundEnd) {
                i++
            }
        } else {
            i++
        }
    }

    // Sort matches by start position
    latexMatches.sortBy { it.first }

    // If we found LaTeX, render with mixed content using Row
    if (latexMatches.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            var lastIndex = 0
            latexMatches.forEach { (start, end, latexContent) ->
                // Render text before LaTeX (if any)
                if (start > lastIndex) {
                    val textBefore = rawText.substring(lastIndex, start)
                    if (textBefore.isNotBlank()) {
                        Text(
                            text = textBefore,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Render LaTeX formula as image
                latexFormula(
                    latex = latexContent.trim(),
                    fontSize = 32f,
                )

                lastIndex = end
            }

            // Render remaining text (if any)
            if (lastIndex < rawText.length) {
                val textAfter = rawText.substring(lastIndex)
                if (textAfter.isNotBlank()) {
                    Text(
                        text = textAfter,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    } else {
        // No LaTeX found, check for dollar notation $ ... $
        if (rawText.contains("$")) {
            // Try to detect $ ... $ patterns
            val dollarRegex = """\$([^\$\n]+?)\$""".toRegex()
            val dollarMatches = dollarRegex.findAll(rawText).toList()

            if (dollarMatches.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    var lastIdx = 0
                    dollarMatches.forEach { match ->
                        // Render text before
                        if (match.range.first > lastIdx) {
                            val textBefore = rawText.substring(lastIdx, match.range.first)
                            if (textBefore.isNotBlank()) {
                                Text(
                                    text = textBefore,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        // Render LaTeX formula
                        latexFormula(
                            latex = match.groupValues[1].trim(),
                            fontSize = 28f,
                        )

                        lastIdx = match.range.last + 1
                    }

                    // Render remaining text
                    if (lastIdx < rawText.length) {
                        val textAfter = rawText.substring(lastIdx)
                        if (textAfter.isNotBlank()) {
                            Text(
                                text = textAfter,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                return
            }
        }

        // No LaTeX at all, render normally with full markdown support
        val annotatedText =
            buildInlineContent(paragraph, inlineCodeBg, linkColor, onLinkClick)
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
}

/**
 * Fix LaTeX content that has been mangled by markdown escape processing.
 *
 * Markdown treats backslash as escape character, so:
 * - `\\` (LaTeX line break) becomes `\` + next char (e.g., `\a_n`)
 * - `\,` (LaTeX thin space) becomes `,`
 *
 * This function attempts to reconstruct the original LaTeX.
 */
private fun fixMarkdownMangledLatex(latex: String): String {
    var fixed = latex

    // Fix: \a_n → a_n  (after line break, letter should not have backslash)
    // Pattern: backslash followed by lowercase letter followed by underscore or caret
    // BUT preserve alignment markers like &= in aligned environments
    fixed = fixed.replace(Regex("""\\([a-z])([_^])""")) { matchResult ->
        val letter = matchResult.groupValues[1]
        val symbol = matchResult.groupValues[2]

        // Don't fix if this is after an alignment marker &
        val startIndex = matchResult.range.first
        if (startIndex > 0 && fixed.getOrNull(startIndex - 1) == '&') {
            matchResult.value // Keep as-is
        } else {
            "$letter$symbol"
        }
    }

    // Fix: \f(x) → f(x)  (function names shouldn't have leading backslash unless they're LaTeX commands)
    fixed = fixed.replace(Regex("""\\([a-z])\("""), "$1(")

    // Fix common markdown escape artifacts after line breaks
    // Pattern: } or \right followed by space and \X where X is a letter
    // BUT don't touch & alignment markers
    fixed = fixed.replace(Regex("""([}]|\\right)\s+\\([a-zA-Z])"""), "$1 $2")

    return fixed
}

@Composable
private fun renderHeading(heading: Heading, onLinkClick: ((url: String) -> Unit)? = null) {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val linkColor = MaterialTheme.colorScheme.tertiary

    val style = when (heading.level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        4 -> MaterialTheme.typography.titleMedium
        5 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.bodyLarge
    }

    Text(
        text = buildInlineContent(heading, inlineCodeBg, linkColor, onLinkClick),
        style = style,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun renderBulletList(list: BulletList, onLinkClick: ((url: String) -> Unit)? = null) {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

    Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                renderListItem(item, "• ", inlineCodeBg, onLinkClick)
            }
            item = item.next
        }
    }
}

@Composable
private fun renderOrderedList(list: OrderedList, onLinkClick: ((url: String) -> Unit)? = null) {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)

    Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
        var item = list.firstChild

        var index = list.markerStartNumber
        while (item != null) {
            if (item is ListItem) {
                renderListItem(item, "$index. ", inlineCodeBg, onLinkClick)
                index++
            }
            item = item.next
        }
    }
}

@Composable
private fun renderListItem(
    item: ListItem,
    marker: String,
    inlineCodeBg: Color,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.tertiary

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        // First, collect inline content and nested blocks
        val inlineContent = mutableListOf<Node>()
        val nestedBlocks = mutableListOf<Node>()

        var child = item.firstChild
        while (child != null) {
            when (child) {
                is BulletList, is OrderedList -> nestedBlocks.add(child)
                else -> inlineContent.add(child)
            }
            child = child.next
        }

        // Render inline content with marker
        if (inlineContent.isNotEmpty()) {
            val annotatedText = buildAnnotatedString {
                append(marker)
                inlineContent.forEach { node ->
                    append(
                        buildInlineContentForNode(
                            node,
                            inlineCodeBg,
                            linkColor,
                            onLinkClick,
                        ),
                    )
                }
            }

            Text(
                text = annotatedText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Render nested lists
        nestedBlocks.forEach { block ->
            when (block) {
                is BulletList -> renderBulletList(block, onLinkClick)
                is OrderedList -> renderOrderedList(block, onLinkClick)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun renderCodeBlock(codeBlock: FencedCodeBlock, viewportTopY: Float? = null, isStreaming: Boolean = false, onRunRequest: ((String, String) -> Unit)? = null, messageId: String? = null) {
    val rawInfo = codeBlock.info?.trim() ?: ""
    // Detect the sentinel injected by closeUnclosedFences() — this block is still being streamed
    val blockIsStreaming = rawInfo.endsWith("|streaming")
    // Strip the sentinel before any downstream use
    val cleanInfo = if (blockIsStreaming) rawInfo.removeSuffix("|streaming") else rawInfo
    val language = cleanInfo.takeIf { it.isNotBlank() }
    val code = codeBlock.literal

    // Show partial code while the code block is still being received
    if (blockIsStreaming) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(
                    width = 1.dp,
                    color = AppComponents.codeBlockBorderColor(),
                    shape = MaterialTheme.shapes.small,
                )
                .clip(MaterialTheme.shapes.small)
                .background(AppComponents.codeBlockBackground()),
        ) {
            codeViewerBlock(
                code = code,
                language = language,
                modifier = Modifier.fillMaxWidth(),
            )
            // Streaming indicator pinned to top-right
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (language != null) language else "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        return
    }
    // Resolved once per code block; null means the Run button should not be shown
    // (either unsupported language or executable not found on PATH)
    val runnableLanguage = remember(language) { RunnableLanguage.resolve(language) }

    // Try to parse as chart data before rendering
    val chartData = remember(code, language) {
        parseChartData(code, language)
    }

    // If we successfully parsed chart data, render it as a chart
    // Skip rendering while streaming to avoid passing incomplete diagram definitions to Mermaid CLI
    if (chartData != null) {
        if (isStreaming) {
            // Show a lightweight placeholder while the response is still being generated
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Rendering diagram...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .padding(vertical = 8.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.medium,
                ),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            mermaidChart(
                data = chartData,
                modifier = Modifier.padding(16.dp),
                entityId = messageId,
            )
        }
        return
    }

    // Render as regular code block
    val backgroundColor = AppComponents.codeBlockBackground()
    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current
    var isHovered by remember { mutableStateOf(false) }
    var showCopyFeedback by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var codeBlockPositionInRoot by remember { mutableStateOf<Offset?>(null) }

    // Calculate button offset - simple logic
    val copyButtonTopOffset = if (viewportTopY != null && codeBlockPositionInRoot != null) {
        val posInRoot = codeBlockPositionInRoot!!
        // If position in root is less than viewport top, the top is scrolled out
        if (posInRoot.y < viewportTopY) {
            // Position button in visible area
            with(density) {
                val offsetPx = viewportTopY - posInRoot.y + 10f
                offsetPx.toDp()
            }
        } else {
            4.dp
        }
    } else {
        4.dp
    }

    val codeBlockShape = MaterialTheme.shapes.small

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                width = 1.dp,
                color = AppComponents.codeBlockBorderColor(),
                shape = codeBlockShape,
            )
            .clip(codeBlockShape)
            .background(backgroundColor)
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .onGloballyPositioned { coordinates ->
                if (coordinates.isAttached) {
                    codeBlockPositionInRoot = coordinates.positionInRoot()
                }
            },
    ) {
        codeViewerBlock(
            code = code,
            language = language,
            modifier = Modifier.fillMaxWidth(),
        )

        // Simple: button inside code block, just adjust offset
        if (isHovered) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = copyButtonTopOffset, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Copy feedback
                if (showCopyFeedback) {
                    Text(
                        text = stringResource("mermaid.feedback.copied"),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Run button — shown only when the language is runnable and its executable is on PATH
                if (runnableLanguage != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        themedTooltip(text = stringResource("code.run")) {
                            IconButton(
                                onClick = {
                                    onRunRequest?.invoke(
                                        runnableLanguage.buildTerminalCommand(code),
                                        runnableLanguage.aliases.first(),
                                    )
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = stringResource("code.run.description"),
                                    modifier = Modifier.size(16.dp).pointerHoverIcon(PointerIcon.Hand),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Copy button
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    themedTooltip(text = stringResource("code.copy")) {
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(codeBlock.literal.trimEnd('\n', '\r')))
                                showCopyFeedback = true
                                coroutineScope.launch {
                                    delay(2000.milliseconds)
                                    showCopyFeedback = false
                                }
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource("code.copy.description"),
                                modifier = Modifier.size(16.dp).pointerHoverIcon(PointerIcon.Hand),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun renderBlockQuote(blockQuote: BlockQuote, viewportTopY: Float? = null, onRunRequest: ((String, String) -> Unit)? = null, onLinkClick: ((url: String) -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp),
    ) {
        renderNode(blockQuote, viewportTopY, false, onRunRequest, null, onLinkClick)
    }
}

/**
 * Reusable download button overlay for images.
 * Styled to match the copy button from code blocks.
 * [onClick] is a plain lambda — the caller is responsible for launching any coroutine work.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun imageDownloadButton(
    isVisible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isVisible) {
        Box(
            modifier = modifier
                .clip(MaterialTheme.shapes.small)
                .background(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small,
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { onClick() }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download image",
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun renderImage(image: Image) {
    val context = LocalPlatformContext.current
    val destination = image.destination
    var isHovered by remember { mutableStateOf(false) }
    // Scope lives as long as this composable, so downloads survive the hover button disappearing
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        // Check if it's a base64 data URL
        if (destination.startsWith("data:image/")) {
            // Decode base64 outside composable context
            val imageResult = remember(destination) {
                try {
                    val base64String = destination.substringAfter("base64,", "")
                    if (base64String.isNotEmpty()) {
                        val imageBytes = Base64.getDecoder().decode(base64String)
                        val bufferedImage = ImageIO.read(ByteArrayInputStream(imageBytes))

                        if (bufferedImage != null) {
                            Result.success(Pair(bufferedImage.toComposeImageBitmap(), imageBytes))
                        } else {
                            Result.failure(Exception("Failed to decode image"))
                        }
                    } else {
                        Result.failure(Exception("Invalid base64 data"))
                    }
                } catch (e: Exception) {
                    log.error("Error decoding base64 image", e)
                    Result.failure(e)
                }
            }

            imageResult.fold(
                onSuccess = { (bitmap, imageBytes) ->
                    Box(
                        modifier = Modifier
                            .wrapContentSize(Alignment.TopStart)
                            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = image.title ?: extractTextContent(image),
                            modifier = Modifier.clip(MaterialTheme.shapes.small),
                        )

                        imageDownloadButton(
                            isVisible = isHovered,
                            onClick = { coroutineScope.launch { downloadImage(imageData = imageBytes) } },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 6.dp, bottom = 6.dp),
                        )
                    }
                },
                onFailure = { error ->
                    Text(
                        text = "Error loading image: ${error.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )
                },
            )
        } else {
            // Regular URL - use AsyncImage
            Box(
                modifier = Modifier
                    .wrapContentSize(Alignment.TopStart)
                    .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { isHovered = false },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(destination)
                        .crossfade(true)
                        .build(),
                    contentDescription = image.title ?: extractTextContent(image),
                    modifier = Modifier.clip(MaterialTheme.shapes.small),
                )

                imageDownloadButton(
                    isVisible = isHovered,
                    onClick = { coroutineScope.launch { downloadImage(imageUrl = destination) } },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 6.dp),
                )
            }
        }

        // Show caption if title or alt text exists
        val caption = image.title ?: extractTextContent(image)
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun renderVideo(videoUrl: String) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { uriHandler.openUri(videoUrl) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play video",
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape,
                        )
                        .padding(16.dp),
                    tint = Color.White,
                )
                Text(
                    text = "Click to play video",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Text(
            text = videoUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

@Composable
private fun renderTable(table: TableBlock) {
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, borderColor),
    ) {
        var child = table.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> {
                    // Render table header
                    var headerRow = child.firstChild
                    while (headerRow != null) {
                        if (headerRow is TableRow) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    ),
                            ) {
                                var cell = headerRow.firstChild
                                while (cell != null) {
                                    if (cell is TableCell) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .border(1.dp, borderColor)
                                                .padding(8.dp),
                                            contentAlignment = Alignment.TopStart,
                                        ) {
                                            Text(
                                                text = extractCellText(cell),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                    cell = cell.next
                                }
                            }
                        }
                        headerRow = headerRow.next
                    }
                }

                is TableBody -> {
                    // Render table body
                    var bodyRow = child.firstChild
                    while (bodyRow != null) {
                        if (bodyRow is TableRow) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                            ) {
                                var cell = bodyRow.firstChild
                                while (cell != null) {
                                    if (cell is TableCell) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .border(1.dp, borderColor)
                                                .padding(8.dp),
                                            contentAlignment = Alignment.TopStart,
                                        ) {
                                            Text(
                                                text = extractCellText(cell),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                    cell = cell.next
                                }
                            }
                        }
                        bodyRow = bodyRow.next
                    }
                }
            }
            child = child.next
        }
    }
}

/**
 * Extract plain text content from a table cell node.
 */
private fun extractCellText(node: Node): String {
    val builder = StringBuilder()
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MarkdownText -> builder.append(child.literal)
            is Paragraph -> builder.append(extractCellText(child))
            is StrongEmphasis -> builder.append(extractCellText(child))
            is Emphasis -> builder.append(extractCellText(child))
            is Code -> builder.append(child.literal)
            else -> builder.append(extractCellText(child))
        }
        child = child.next
    }
    return builder.toString()
}

private fun buildInlineContent(
    node: Node,
    inlineCodeBg: Color,
    linkColor: Color,
    onLinkClick: ((url: String) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MarkdownText -> {
                val text = child.literal
                var lastIndex = 0

                // Detect inline math expressions wrapped in \[ \] or [ ] or $ $ or $$ $$
                val mathRanges = mutableListOf<Triple<IntRange, String, Boolean>>() // Triple(range, content, isDisplayMath)

                // Find display math \[ ... \] or [ ... ] (markdown parser might strip the backslash)
                var i = 0
                while (i < text.length) {
                    // Check for \[ or just [
                    val hasBackslash = i < text.length - 1 && text[i] == '\\' && text[i + 1] == '['
                    val justBracket = text[i] == '[' && (i == 0 || text[i - 1] != '\\')

                    if (hasBackslash || justBracket) {
                        // Found start of display math
                        val start = i
                        val contentStart = if (hasBackslash) i + 2 else i + 1
                        var j = contentStart
                        var foundEnd = false

                        while (j < text.length) {
                            // Check for \] or just ]
                            val hasEndBackslash = j < text.length - 1 && text[j] == '\\' && text[j + 1] == ']'
                            val justEndBracket = text[j] == ']' && (j == 0 || text[j - 1] != '\\')

                            if (hasEndBackslash || justEndBracket) {
                                // Found end of display math
                                val content = text.substring(contentStart, j)
                                // Only treat as math if it contains typical math content
                                if (content.contains(Regex("[a-zA-Z\\\\^_{}=+\\-*/()]"))) {
                                    val endIndex = if (hasEndBackslash) j + 1 else j
                                    mathRanges.add(Triple(IntRange(start, endIndex), content, true))
                                    i = endIndex + 1 // Skip past the math expression
                                    foundEnd = true
                                    break
                                }
                            }
                            j++
                        }

                        // If we didn't find the end, just move past the opening bracket
                        if (!foundEnd) {
                            i++
                        }
                    } else {
                        i++
                    }
                }

                // Find inline math \( ... \)
                i = 0
                while (i < text.length - 1) {
                    if (text[i] == '\\' && text[i + 1] == '(') {
                        val start = i
                        val contentStart = i + 2
                        var j = contentStart
                        var foundEnd = false

                        while (j < text.length - 1) {
                            if (text[j] == '\\' && text[j + 1] == ')') {
                                val content = text.substring(contentStart, j)
                                // Only treat as math if it contains typical math content
                                if (content.contains(Regex("[a-zA-Z\\\\^_{}=+\\-*/()]"))) {
                                    // Check for overlap
                                    val overlaps = mathRanges.any { (range, _, _) ->
                                        start in range || j + 1 in range ||
                                            range.first in start..j + 1 || range.last in start..j + 1
                                    }

                                    if (!overlaps) {
                                        mathRanges.add(Triple(IntRange(start, j + 1), content, false))
                                    }
                                    i = j + 2
                                    foundEnd = true
                                    break
                                }
                            }
                            j++
                        }

                        if (!foundEnd) {
                            i++
                        }
                    } else {
                        i++
                    }
                }

                // Find dollar notation math $$ ... $$ and $ ... $
                val dollarRegex = """\$\$(.+?)\$\$|\$([^\$\n]+?)\$""".toRegex()
                dollarRegex.findAll(text).forEach { match ->
                    // Check if this range doesn't overlap with already found ranges
                    val overlaps = mathRanges.any { (range, _, _) ->
                        match.range.first in range || match.range.last in range ||
                            range.first in match.range || range.last in match.range
                    }
                    if (!overlaps) {
                        val content = match.groupValues[1].ifEmpty { match.groupValues[2] }
                        val isDisplayMath = match.value.startsWith("$$")
                        mathRanges.add(Triple(match.range, content, isDisplayMath))
                    }
                }

                // Sort by position
                mathRanges.sortBy { it.first.first }

                // Reset lastIndex for building the final string
                lastIndex = 0

                // Build the annotated string with math expressions
                mathRanges.forEach { (range, content, _) ->
                    // Append text before the math expression
                    if (range.first > lastIndex) {
                        append(text.substring(lastIndex, range.first))
                    }

                    // Render the math expression
                    // For now, we'll use a placeholder and render the image separately
                    // because Compose Text doesn't support inline images directly
                    if (content.isNotEmpty()) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                background = inlineCodeBg.copy(alpha = 0.2f),
                            ),
                        ) {
                            append(" ")
                            append(parseLatexMath(content.trim()))
                            append(" ")
                        }
                    }

                    lastIndex = range.last + 1
                }

                // Append remaining text after the last match
                if (lastIndex < text.length) {
                    append(text.substring(lastIndex))
                }
            }

            is StrongEmphasis -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(buildInlineContent(child, inlineCodeBg, linkColor, onLinkClick))
                }
            }

            is Emphasis -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(buildInlineContent(child, inlineCodeBg, linkColor, onLinkClick))
                }
            }

            is Code -> {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = inlineCodeBg,
                    ),
                ) {
                    append(" ${child.literal} ")
                }
            }

            is FencedCodeBlock -> {
                // Treat fenced code blocks as inline code when they appear in inline contexts
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = inlineCodeBg,
                    ),
                ) {
                    append(" ${child.literal} ")
                }
            }

            is Link -> {
                // Add link annotation for clickable links using the new LinkAnnotation API
                // Build styled content for link children (e.g., inline code with backticks)
                val linkContent = buildInlineContent(child, inlineCodeBg, linkColor, onLinkClick)
                val displayContent = linkContent.ifEmpty {
                    AnnotatedString(child.destination)
                }

                val destination = child.destination
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                )

                if (destination.startsWith("file://") && onLinkClick != null) {
                    // Intercept file:// links — fire callback instead of opening OS browser
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = destination,
                            styles = linkStyles,
                            linkInteractionListener = { onLinkClick(destination) },
                        ),
                    ) {
                        append(displayContent)
                    }
                } else {
                    withLink(
                        LinkAnnotation.Url(
                            url = destination,
                            styles = linkStyles,
                        ),
                    ) {
                        append(displayContent)
                    }
                }
            }

            is Image -> {
                // For inline images, render as a clickable link with the alt text
                val altText = extractTextContent(child)
                val displayText = altText.ifEmpty { child.destination }

                withLink(
                    LinkAnnotation.Url(
                        url = child.destination,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) {
                    append(displayText)
                }
            }

            is HardLineBreak, is SoftLineBreak -> append("\n")

            is Paragraph -> append(buildInlineContent(child, inlineCodeBg, linkColor, onLinkClick))

            else -> append(buildInlineContent(child, inlineCodeBg, linkColor, onLinkClick))
        }
        child = child.next
    }
}

/**
 * Build inline content for a single node (used by list items).
 */
private fun buildInlineContentForNode(
    node: Node,
    inlineCodeBg: Color,
    linkColor: Color,
    onLinkClick: ((url: String) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    append(buildInlineContent(node, inlineCodeBg, linkColor, onLinkClick))
}

/**
 * Parse LaTeX math expressions and convert to styled AnnotatedString.
 * Handles superscripts, subscripts, and Greek letter symbols.
 */
private fun parseLatexMath(latex: String): AnnotatedString = buildAnnotatedString {
    var text = latex

    // Handle superscripts (e.g., e^{i\theta} or x^2)
    val superscriptRegex = """\^(\{[^}]+\}|\S)""".toRegex()
    text = superscriptRegex.replace(text) { matchResult ->
        val content = matchResult.groupValues[1].removeSurrounding("{", "}")
        "^($content)"
    }

    // Handle subscripts (e.g., x_{n})
    val subscriptRegex = """_(\{[^}]+\}|\S)""".toRegex()
    text = subscriptRegex.replace(text) { matchResult ->
        val content = matchResult.groupValues[1].removeSurrounding("{", "}")
        "_($content)"
    }

    // Replace Greek letters and special mathematical symbols
    val symbols = mapOf(
        "\\theta" to "θ",
        "\\pi" to "π",
        "\\alpha" to "α",
        "\\beta" to "β",
        "\\gamma" to "γ",
        "\\delta" to "δ",
        "\\epsilon" to "ε",
        "\\zeta" to "ζ",
        "\\eta" to "η",
        "\\lambda" to "λ",
        "\\mu" to "μ",
        "\\nu" to "ν",
        "\\xi" to "ξ",
        "\\rho" to "ρ",
        "\\sigma" to "σ",
        "\\tau" to "τ",
        "\\phi" to "φ",
        "\\chi" to "χ",
        "\\psi" to "ψ",
        "\\omega" to "ω",
        "\\Theta" to "Θ",
        "\\Pi" to "Π",
        "\\Sigma" to "Σ",
        "\\Phi" to "Φ",
        "\\Psi" to "Ψ",
        "\\Omega" to "Ω",
        "\\Delta" to "Δ",
        "\\Gamma" to "Γ",
        "\\Lambda" to "Λ",
        "\\infty" to "∞",
        "\\sum" to "∑",
        "\\prod" to "∏",
        "\\int" to "∫",
        "\\sqrt" to "√",
        "\\cdot" to "⋅",
        "\\times" to "×",
        "\\div" to "÷",
        "\\pm" to "±",
        "\\mp" to "∓",
        "\\neq" to "≠",
        "\\leq" to "≤",
        "\\geq" to "≥",
        "\\approx" to "≈",
        "\\equiv" to "≡",
        "\\in" to "∈",
        "\\notin" to "∉",
        "\\subset" to "⊂",
        "\\supset" to "⊃",
        "\\cup" to "∪",
        "\\cap" to "∩",
        "\\forall" to "∀",
        "\\exists" to "∃",
        "\\nabla" to "∇",
        "\\partial" to "∂",
        "\\propto" to "∝",
        "\\rightarrow" to "→",
        "\\leftarrow" to "←",
        "\\leftrightarrow" to "↔",
        "\\Rightarrow" to "⇒",
        "\\Leftarrow" to "⇐",
        "\\Leftrightarrow" to "⇔",
        "\\cos" to "cos",
        "\\sin" to "sin",
        "\\tan" to "tan",
        "\\log" to "log",
        "\\ln" to "ln",
        "\\exp" to "exp",
    )

    symbols.forEach { (latex, unicode) ->
        text = text.replace(latex, unicode)
    }

    // Parse and apply styles for superscripts and subscripts
    var i = 0
    while (i < text.length) {
        when {
            // Superscript
            text[i] == '^' && i + 1 < text.length && text[i + 1] == '(' -> {
                val end = text.indexOf(')', i + 2)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            fontSize = 11.sp,
                            baselineShift = BaselineShift(0.5f),
                        ),
                    ) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }

            // Subscript
            text[i] == '_' && i + 1 < text.length && text[i + 1] == '(' -> {
                val end = text.indexOf(')', i + 2)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            fontSize = 11.sp,
                            baselineShift = BaselineShift(-0.3f),
                        ),
                    ) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }

            else -> {
                append(text[i])
                i++
            }
        }
    }
}

/**
 * Extract text content from a node by collecting all MarkdownText children.
 * Used for extracting link text, image alt text, etc.
 */
private fun extractTextContent(node: Node): String {
    val builder = StringBuilder()
    var child = node.firstChild
    while (child != null) {
        if (child is MarkdownText) {
            builder.append(child.literal)
        }
        child = child.next
    }
    return builder.toString()
}

/**
 * Check if a URL is a video URL based on file extension.
 */
private fun isVideoUrl(url: String): Boolean {
    val videoExtensions = listOf(".mp4", ".webm", ".mov", ".avi", ".mkv", ".m4v", ".flv", ".wmv")
    return videoExtensions.any { url.lowercase().endsWith(it) }
}

/**
 * Extract video URL from paragraph if it's the only content.
 * Returns the video URL if found, null otherwise.
 */
private fun extractVideoUrl(paragraph: Paragraph): String? {
    var child = paragraph.firstChild
    var linkFound: String? = null
    var hasOtherContent = false

    while (child != null) {
        when (child) {
            is Link -> {
                val destination = child.destination
                if (isVideoUrl(destination)) {
                    linkFound = destination
                } else {
                    hasOtherContent = true
                }
            }

            is MarkdownText -> {
                if (child.literal.trim().isNotEmpty()) {
                    hasOtherContent = true
                }
            }

            else -> hasOtherContent = true
        }
        child = child.next
    }

    return if (!hasOtherContent && linkFound != null) linkFound else null
}

/**
 * Parse chart data from JSON code block or Mermaid diagram.
 * Returns MermaidChartData if the JSON/Mermaid is a valid diagram specification, null otherwise.
 */
private fun parseChartData(code: String, language: String?): MermaidChartData? = when (language?.lowercase()) {
    "mermaid" -> {
        val cleanedCode = code.trim()
        if (cleanedCode.isEmpty()) {
            null
        } else {
            val title = when {
                cleanedCode.contains("sequenceDiagram") -> "Sequence Diagram"
                cleanedCode.contains("classDiagram") -> "Class Diagram"
                cleanedCode.contains("stateDiagram") -> "State Diagram"
                cleanedCode.contains("erDiagram") -> "ER Diagram"
                cleanedCode.contains("gantt") -> "Gantt Chart"
                cleanedCode.contains("pie") -> "Pie Chart"
                cleanedCode.contains("journey") -> "User Journey"
                cleanedCode.startsWith("graph") || cleanedCode.startsWith("flowchart") -> "Flowchart"
                else -> "Mermaid Diagram"
            }
            MermaidChartData(title = title, diagram = cleanedCode, theme = "default")
        }
    }

    "json" -> {
        val cleanedCode = code.trim()
            .replace("```json", "")
            .replace("```", "")
            .trim()
        if (cleanedCode.isEmpty() || cleanedCode.length < 20) {
            null
        } else {
            try {
                json.decodeFromString<MermaidChartData>(cleanedCode)
            } catch (e: Exception) {
                log.trace("Failed to parse Mermaid diagram data (may be incomplete): ${e.message}")
                null
            }
        }
    }

    else -> null
}

/**
 * Downloads an image using a file chooser dialog.
 * Supports both byte arrays (base64 images) and URLs.
 */
private suspend fun downloadImage(
    imageData: ByteArray? = null,
    imageUrl: String? = null,
    defaultFileName: String = "image.png",
) {
    try {
        val (extension, fileName) = if (imageUrl != null) {
            val urlFileName = imageUrl.substringAfterLast('/').substringBefore('?').ifEmpty { defaultFileName }
            val ext = urlFileName.substringAfterLast('.', "png")
            ext to urlFileName
        } else {
            "png" to defaultFileName
        }

        // FileKit.openFileSaver handles its own thread dispatch internally
        val file = FileDialogUtils.pickSavePath(
            suggestedName = fileName.substringBeforeLast('.', fileName),
            extension = extension,
            title = "Save Image",
        ) ?: run {
            log.debug("Save image dialog cancelled")
            return
        }

        // Write the file on an IO thread so we never block the EDT
        withContext(Dispatchers.IO) {
            when {
                imageData != null -> {
                    file.writeBytes(imageData)
                    log.info("Image saved to: {}", file.absolutePath)
                }

                imageUrl != null -> {
                    val url = URI(imageUrl).toURL()
                    url.openStream().use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    log.info("Image downloaded and saved to: {}", file.absolutePath)
                }

                else -> {
                    log.error("No image data or URL provided")
                }
            }
        }
    } catch (e: Exception) {
        log.error("Failed to save image", e)
    }
}
