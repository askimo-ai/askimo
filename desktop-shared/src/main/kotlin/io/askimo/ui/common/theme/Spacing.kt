/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Holds density-scaled spacing values.
 *
 * Base values follow Material Design 3 guidelines and are scaled by [LayoutDensity.scale]:
 * - [micro]      2.dp  — hairline spacing, e.g. between an icon and a tightly stacked badge
 * - [extraSmall] 4.dp  — tight spacing within small components
 * - [small]      8.dp  — standard spacing between related items
 * - [medium]     12.dp — spacing between component groups within a card
 * - [large]      16.dp — card/panel padding and spacing between cards
 * - [extraLarge] 24.dp — major section spacing
 * - [scrollbarGutter] 36.dp — trailing inset for scrollable page content, reserving room
 *   for [AppComponents.scrollbarStyle]'s fixed 6.dp-thick overlay scrollbar so it never
 *   overlaps text. Scales with density like the other tokens; even at the smallest
 *   density (Compact, ×0.7 ≈ 25dp) it stays far larger than the fixed scrollbar thickness.
 */
data class SpacingValues(
    val micro: Dp = 2.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val extraLarge: Dp = 24.dp,
    val scrollbarGutter: Dp = 36.dp,
)

/** Creates [SpacingValues] with all tokens multiplied by [scale]. */
fun SpacingValues(scale: Float) = SpacingValues(
    micro = 2.dp * scale,
    extraSmall = 4.dp * scale,
    small = 8.dp * scale,
    medium = 12.dp * scale,
    large = 16.dp * scale,
    extraLarge = 24.dp * scale,
    scrollbarGutter = 36.dp * scale,
)

/**
 * Composition local holding the current density-scaled spacing tokens.
 * Defaults to Comfortable (scale = 1.0) so the app works without explicit provision.
 */
val LocalSpacing = compositionLocalOf { SpacingValues() }

/**
 * Convenience object. All properties delegate to [LocalSpacing.current],
 * so they automatically reflect whichever density is active.
 *
 * Must be accessed from a `@Composable` scope.
 */
object Spacing {
    val micro: Dp @Composable get() = LocalSpacing.current.micro
    val extraSmall: Dp @Composable get() = LocalSpacing.current.extraSmall
    val small: Dp @Composable get() = LocalSpacing.current.small
    val medium: Dp @Composable get() = LocalSpacing.current.medium
    val large: Dp @Composable get() = LocalSpacing.current.large
    val extraLarge: Dp @Composable get() = LocalSpacing.current.extraLarge
    val scrollbarGutter: Dp @Composable get() = LocalSpacing.current.scrollbarGutter
}
