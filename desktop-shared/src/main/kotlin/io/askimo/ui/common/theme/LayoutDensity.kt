/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.runtime.compositionLocalOf

enum class LayoutDensity(val scale: Float) {
    COMFORTABLE(1.0f),
    COMPACT(0.7f),
    RELAXED(1.2f),
    SPACIOUS(1.4f),
    ;

    companion object {
        fun fromPreference(value: String?): LayoutDensity = try {
            if (value.isNullOrBlank()) COMFORTABLE else valueOf(value)
        } catch (_: IllegalArgumentException) {
            COMFORTABLE
        }
    }
}

val LocalLayoutDensity = compositionLocalOf { LayoutDensity.COMFORTABLE }
