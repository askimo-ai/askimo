/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.theme.AppComponents

/**
 * Reusable code/text viewer with:
 * - Syntax-highlighted content (via [CodeHighlighter]) when a [language] is supplied
 * - Line-number gutter aligned with each code line
 * - No word wrap — horizontal scrolling for long lines with a visible HorizontalScrollbar
 *
 * Vertical scrolling is intentionally left to the **caller** (e.g. a parent `verticalScroll`
 * or a `LazyColumn`). This avoids unbounded-height crashes when placed inside a scrollable
 * Compose layout.
 *
 * @param code     The raw text / code to display.
 * @param language Optional language hint for syntax highlighting (e.g. "kotlin", "json").
 *                 Pass `null` to render plain monospace text.
 * @param modifier Modifier applied to the outermost [Box].
 */
@Composable
fun codeViewerBlock(
    code: String,
    language: String? = null,
    hScrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    val isDark = AppComponents.isCodeBlockDark()
    val theme = if (isDark) CodeHighlighter.darkTheme() else CodeHighlighter.lightTheme()
    val highlightedCode = CodeHighlighter.highlight(code = code, language = language, theme = theme)

    val backgroundColor = AppComponents.codeBlockBackground()
    val contentColor = AppComponents.codeBlockContentColor()

    val lines = code.lines()
    val lineCount = lines.size

    val lineNumberColor = Color(contentColor.red, contentColor.green, contentColor.blue, 0.4f)
    val lineNumberWidth = when {
        lineCount >= 1000 -> 52.dp
        lineCount >= 100 -> 42.dp
        else -> 32.dp
    }
    val gutterBackground = backgroundColor.let { base ->
        val factor = if (isDark) 0.80f else 0.93f
        Color(
            red = (base.red * factor).coerceIn(0f, 1f),
            green = (base.green * factor).coerceIn(0f, 1f),
            blue = (base.blue * factor).coerceIn(0f, 1f),
            alpha = base.alpha,
        )
    }
    val dividerColor = Color(lineNumberColor.red, lineNumberColor.green, lineNumberColor.blue, 0.15f)

    // Outer Column: code row + scrollbar below
    Column(modifier = modifier.background(backgroundColor)) {
        // ── Code row (gutter + content) ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // ── Gutter: line numbers ──────────────────────────────────────────
            Column(
                modifier = Modifier
                    .width(lineNumberWidth)
                    .fillMaxHeight()
                    .background(gutterBackground)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                lines.forEachIndexed { index, _ ->
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = lineNumberColor,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(dividerColor),
            )

            Text(
                text = highlightedCode,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = contentColor,
                softWrap = false,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScrollState)
                    .padding(top = 12.dp, bottom = 12.dp, start = 12.dp, end = 12.dp),
            )
        }

        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(hScrollState),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 6.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 150,
                unhoverColor = contentColor.copy(alpha = 0.20f),
                hoverColor = contentColor.copy(alpha = 0.50f),
            ),
        )
    }
}
