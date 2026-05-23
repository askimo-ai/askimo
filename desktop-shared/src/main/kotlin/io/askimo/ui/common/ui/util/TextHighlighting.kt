/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * Highlights all occurrences of a search query in text.
 *
 * @param text The text to highlight
 * @param query The search query to highlight (case-insensitive)
 * @param highlightColor The color to use for highlighting
 * @param isActiveResult Whether this message is the currently active search result
 * @param activeHighlightColor The color to use for the active result (only used if isActiveResult is true)
 * @return AnnotatedString with highlighted matches
 */
fun markdownToPlainText(markdown: String): String {
    val parser = Parser.builder().build()
    val document = parser.parse(markdown)
    val sb = StringBuilder()
    document.accept(
        object : AbstractVisitor() {
            override fun visit(text: Text) {
                sb.append(text.literal)
                visitChildren(text)
            }

            override fun visit(code: Code) {
                sb.append(code.literal)
            }

            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                sb.append(fencedCodeBlock.literal)
            }

            override fun visit(indentedCodeBlock: IndentedCodeBlock) {
                sb.append(indentedCodeBlock.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                sb.append(' ')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                sb.append('\n')
            }
        },
    )
    return sb.toString().trim()
}

fun highlightSearchText(
    text: String,
    query: String,
    highlightColor: Color,
    isActiveResult: Boolean = false,
    activeHighlightColor: Color = highlightColor,
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var currentIndex = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        // Use active color if this is the active result, otherwise use normal color
        val bgColor = if (isActiveResult) activeHighlightColor else highlightColor

        // Choose foreground: black on light backgrounds, white on dark ones
        val fgColor = if (bgColor.luminance() > 0.3f) Color.Black else Color.White

        while (currentIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)

            if (matchIndex == -1) {
                // No more matches, append the rest of the text
                append(text.substring(currentIndex))
                break
            }

            // Append text before the match
            if (matchIndex > currentIndex) {
                append(text.substring(currentIndex, matchIndex))
            }

            // Append the highlighted match
            withStyle(
                style = SpanStyle(
                    background = bgColor,
                    color = fgColor,
                ),
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }

            currentIndex = matchIndex + query.length
        }
    }
}
