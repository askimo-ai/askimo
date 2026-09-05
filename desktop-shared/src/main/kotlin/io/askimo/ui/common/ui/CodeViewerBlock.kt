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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.LocalCodeFontFamily

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
    val codeFontFamily = LocalCodeFontFamily.current
    val isDark = AppColors.isCodeBlockDark()
    val theme = if (isDark) CodeHighlighter.darkTheme() else CodeHighlighter.lightTheme()
    val highlightedCode = CodeHighlighter.highlight(
        code = code,
        language = language,
        theme = theme,
        codeFontFamily = codeFontFamily,
    )

    val backgroundColor = AppColors.codeBlockBackground()
    val contentColor = AppColors.codeBlockContentColor()

    val lines = code.lines()
    val lineCount = lines.size

    val lineNumberColor = Color(contentColor.red, contentColor.green, contentColor.blue, 0.4f)

    // Measure the width of the widest line-number string using the actual code font so
    // the gutter always fits — regardless of digit count — without hard-coded breakpoints.
    val textMeasurer = rememberTextMeasurer()
    val lineNumberStyle = AppTextStyles.body.copy(fontFamily = codeFontFamily)
    val density = LocalDensity.current
    val lineNumberWidth: Dp = remember(lineCount, codeFontFamily, density) {
        val digitCount = lineCount.toString().length
        // "8" repeated is the widest digit in most monospace fonts.
        val widestLabel = "8".repeat(digitCount)
        val measured = textMeasurer.measure(widestLabel, lineNumberStyle)
        val textWidthDp = with(density) { measured.size.width.toDp() }
        // end-padding (8 dp) + measured text + small left breathing room (4 dp), min 32 dp
        (textWidthDp + 8.dp + 4.dp).coerceAtLeast(32.dp)
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
            DisableSelection {
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
                            style = AppTextStyles.body,
                            fontFamily = codeFontFamily,
                            color = lineNumberColor,
                            softWrap = false,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
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
                style = AppTextStyles.body,
                fontFamily = codeFontFamily,
                color = contentColor,
                softWrap = false,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScrollState)
                    .padding(top = 12.dp, bottom = 12.dp, start = 12.dp, end = 12.dp),
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth.value.isFinite()) {
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
                        unhoverColor = AppColors.surfaceColor(AppColors.Elevation.RECESSED),
                        hoverColor = AppColors.tertiaryIconColor(),
                    ),
                )
            }
        }
    }
}
