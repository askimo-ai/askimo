/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Maximum number of characters shown in a tooltip before truncating with "…". */
const val TOOLTIP_MAX_CHARS = 1200

/** Maximum width of the tooltip popup. */
private val TOOLTIP_MAX_WIDTH = 640.dp

/** Delay before showing the tooltip after the cursor enters the anchor. */
private val TOOLTIP_SHOW_DELAY = 400.milliseconds

/**
 * Delay before hiding the tooltip after the cursor leaves the anchor.
 * Long enough to survive the brief gap between anchor and popup.
 */
private val TOOLTIP_HIDE_DELAY = 150.milliseconds

/** Controls which side the tooltip appears on relative to its anchor. */
enum class TooltipPlacement {
    /** Default: above when in bottom half of screen, below otherwise. */
    AUTO,

    /** Always to the left of the anchor. */
    LEFT,

    /** Always to the right of the anchor. */
    RIGHT,
}

/**
 * A reusable tooltip component that follows the application theme.
 * Automatically positions itself to avoid overlapping with the component and screen edges.
 *
 * Long texts are truncated at [maxChars] characters and an ellipsis is appended so the
 * tooltip never grows beyond a readable size. Use the default [TOOLTIP_MAX_CHARS] for
 * consistency across the app.
 *
 * @param text The text to display in the tooltip
 * @param maxChars Maximum characters to show before truncating (default [TOOLTIP_MAX_CHARS])
 * @param placement Controls where the tooltip appears relative to the anchor (default [TooltipPlacement.AUTO])
 * @param modifier Optional modifier for the TooltipBox
 * @param content The composable content that the tooltip wraps
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun themedTooltip(
    text: String,
    maxChars: Int = TOOLTIP_MAX_CHARS,
    placement: TooltipPlacement = TooltipPlacement.AUTO,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (text.isBlank()) {
        content()
        return
    }

    val displayText = remember(text, maxChars) {
        if (text.length > maxChars) text.take(maxChars) + "…" else text
    }

    val tooltipState = rememberTooltipState(isPersistent = true)
    var isHovered by remember { mutableStateOf(false) }

    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(TOOLTIP_SHOW_DELAY)
            tooltipState.show()
        } else {
            delay(TOOLTIP_HIDE_DELAY)
            tooltipState.dismiss()
        }
    }

    val windowInfo = LocalWindowInfo.current
    val containerHeight = windowInfo.containerSize.height.toFloat()

    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = remember(containerHeight, placement) {
                SmartTooltipPositionProvider(maxHeightPx = containerHeight, placement = placement)
            },
            tooltip = {
                Surface(
                    modifier = Modifier.padding(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = displayText,
                        modifier = Modifier
                            .widthIn(max = TOOLTIP_MAX_WIDTH)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true,
                    )
                }
            },
            state = tooltipState,
        ) {
            Box(
                modifier = Modifier
                    .hoverable(
                        interactionSource = remember { MutableInteractionSource() },
                        enabled = true,
                    )
                    .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { isHovered = false },
            ) {
                content()
            }
        }
    }
}

/**
 * A tooltip variant that accepts arbitrary composable content in the popup instead of a plain string.
 * Use this for rich tooltips (multi-line, structured content, etc.).
 *
 * @param placement Controls where the tooltip appears relative to the anchor
 * @param modifier Optional modifier for the TooltipBox
 * @param tooltipContent The composable to render inside the tooltip popup
 * @param content The anchor composable the tooltip wraps
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun themedRichTooltip(
    placement: TooltipPlacement = TooltipPlacement.AUTO,
    modifier: Modifier = Modifier,
    tooltipContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    var isHovered by remember { mutableStateOf(false) }

    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(TOOLTIP_SHOW_DELAY)
            tooltipState.show()
        } else {
            delay(TOOLTIP_HIDE_DELAY)
            tooltipState.dismiss()
        }
    }

    val windowInfo = LocalWindowInfo.current
    val containerHeight = windowInfo.containerSize.height.toFloat()

    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = remember(containerHeight, placement) {
                SmartTooltipPositionProvider(maxHeightPx = containerHeight, placement = placement)
            },
            tooltip = {
                Surface(
                    modifier = Modifier.padding(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = 4.dp,
                ) {
                    tooltipContent()
                }
            },
            state = tooltipState,
        ) {
            Box(
                modifier = Modifier
                    .hoverable(
                        interactionSource = remember { MutableInteractionSource() },
                        enabled = true,
                    )
                    .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { isHovered = false },
            ) {
                content()
            }
        }
    }
}

/**
 * Smart tooltip position provider that automatically positions the tooltip
 * to avoid overlapping with the component and screen edges.
 */
private class SmartTooltipPositionProvider(
    private val maxHeightPx: Float,
    private val placement: TooltipPlacement = TooltipPlacement.AUTO,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val spacing = 8

        return when (placement) {
            TooltipPlacement.LEFT -> IntOffset(
                x = anchorBounds.left - popupContentSize.width - spacing,
                y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2,
            )

            TooltipPlacement.RIGHT -> IntOffset(
                x = anchorBounds.right + spacing,
                y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2,
            )

            TooltipPlacement.AUTO -> {
                val isInBottomHalf = anchorBounds.top > maxHeightPx / 2
                if (isInBottomHalf) {
                    IntOffset(
                        x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2,
                        y = anchorBounds.top - popupContentSize.height - spacing,
                    )
                } else {
                    IntOffset(
                        x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2,
                        y = anchorBounds.bottom + spacing,
                    )
                }
            }
        }
    }
}
