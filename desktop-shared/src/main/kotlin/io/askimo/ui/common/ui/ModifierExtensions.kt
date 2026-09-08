/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Modifier extension for creating clickable cards with rounded corners and hover effect.
 * Commonly used in settings screens for option cards.
 *
 * @param cornerRadius Corner radius matching the card's shape (default 12.dp)
 * @param onClick Callback invoked when the card is clicked
 * @return Modified Modifier with click, clip, and hover effects
 */
fun Modifier.clickableCard(
    cornerRadius: Dp = 12.dp,
    onClick: () -> Unit,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .clickable(onClick = onClick)
        .pointerHoverIcon(PointerIcon.Hand)
}

/**
 * Draws a clearly visible outline around this composable while it has keyboard focus —
 * intended for small icon-only controls (mic toggle, send, voice playback, etc.) whose default
 * Material3 focus indication (a faint ripple/overlay) doesn't reliably meet WCAG 2.4.7's
 * "visible focus indicator" contrast expectations, which matters for keyboard-only and
 * screen-reader users who can't rely on hover to find interactive controls.
 *
 * Purely visual — does not itself make the component focusable/clickable. [interactionSource]
 * must be the *same* [MutableInteractionSource] passed to the target `IconButton`'s own
 * `interactionSource` parameter, otherwise focus state is never observed here (Compose reports
 * focus/press/hover events only to the interaction source actually wired into the focusable
 * node, not to an unrelated one created independently).
 *
 * Usage:
 * ```
 * val interactionSource = remember { MutableInteractionSource() }
 * IconButton(
 *     onClick = ...,
 *     interactionSource = interactionSource,
 *     modifier = Modifier.accessibleFocusable(interactionSource = interactionSource),
 * ) { ... }
 * ```
 *
 * @param shape Matches the target control's own shape so the ring hugs its bounds (default:
 *   circular, matching `IconButton`'s default ripple/touch-target shape).
 */
@Composable
fun Modifier.accessibleFocusable(
    interactionSource: MutableInteractionSource,
    shape: Shape = CircleShape,
): Modifier {
    val isFocused by interactionSource.collectIsFocusedAsState()
    return this.then(
        if (isFocused) {
            Modifier.border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = shape)
        } else {
            Modifier
        },
    )
}
