/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

class AccentColorSchemeTest {

    private val seeds = listOf(
        Color(0xFFF59E0B), // Amber
        Color(0xFF8B5CF6), // Violet
        Color(0xFF10B981), // Emerald
    )

    @Test
    fun `accent scheme keeps required contrast in light and dark`() {
        seeds.forEach { seed ->
            val light = applyAccentToColorScheme(seed, isDark = false)
            val dark = applyAccentToColorScheme(seed, isDark = true)

            assertContrastAtLeast(light.primary, light.onPrimary, 4.5f, "light primary/onPrimary")
            assertContrastAtLeast(light.secondaryContainer, light.onSecondaryContainer, 4.5f, "light secondaryContainer/onSecondaryContainer")
            assertContrastAtLeast(light.surface, light.onSurface, 7.0f, "light surface/onSurface")

            assertContrastAtLeast(dark.primary, dark.onPrimary, 4.5f, "dark primary/onPrimary")
            assertContrastAtLeast(dark.secondaryContainer, dark.onSecondaryContainer, 4.5f, "dark secondaryContainer/onSecondaryContainer")
            assertContrastAtLeast(dark.surface, dark.onSurface, 7.0f, "dark surface/onSurface")
        }
    }

    @Test
    fun `accent scheme avoids flat container collapse`() {
        seeds.forEach { seed ->
            val light = applyAccentToColorScheme(seed, isDark = false)
            val dark = applyAccentToColorScheme(seed, isDark = true)

            assertLuminanceGapAtLeast(light.secondaryContainer, light.surface, 0.03f, "light secondaryContainer vs surface")
            assertLuminanceGapAtLeast(light.tertiaryContainer, light.surface, 0.03f, "light tertiaryContainer vs surface")
            assertLuminanceGapAtLeast(dark.secondaryContainer, dark.surface, 0.03f, "dark secondaryContainer vs surface")
            assertLuminanceGapAtLeast(dark.tertiaryContainer, dark.surface, 0.03f, "dark tertiaryContainer vs surface")
        }
    }

    private fun assertContrastAtLeast(background: Color, foreground: Color, min: Float, label: String) {
        val ratio = contrastRatio(background, foreground)
        assertTrue(ratio >= min, "$label contrast $ratio < $min")
    }

    private fun assertLuminanceGapAtLeast(a: Color, b: Color, min: Float, label: String) {
        val gap = kotlin.math.abs(a.luminance() - b.luminance())
        assertTrue(gap >= min, "$label luminance gap $gap < $min")
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val l1 = a.luminance()
        val l2 = b.luminance()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return ((lighter + 0.05f) / (darker + 0.05f))
    }
}
