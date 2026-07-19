/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop

import io.askimo.core.logging.currentFileLogger
import java.io.File

private val log = currentFileLogger()

/**
 * Resolves the best UI scale factor for Linux HiDPI displays when no user preference
 * has been saved yet.
 */
internal object LinuxUiScaleResolver {

    data class ScaleDecision(val scale: Float, val source: String)

    /** Production entry point — reads real env vars and /sys/class/drm. */
    fun resolve(): ScaleDecision = resolve(System.getenv() ?: emptyMap(), readDrmModes())

    /** Testable entry point with injected inputs. */
    fun resolve(env: Map<String, String>, drmModes: List<String>): ScaleDecision {
        // 1. GDK_SCALE × GDK_DPI_SCALE
        val gdkScale = env["GDK_SCALE"]?.toFloatOrNull()
        val gdkDpiScale = env["GDK_DPI_SCALE"]?.toFloatOrNull()
        if (gdkScale != null && gdkDpiScale != null) {
            return ScaleDecision((gdkScale * gdkDpiScale).coerceAtLeast(1.0f), "GDK_SCALE*GDK_DPI_SCALE")
        }
        if (gdkScale != null) {
            return ScaleDecision(gdkScale.coerceAtLeast(1.0f), "GDK_SCALE")
        }

        // 2. QT_SCREEN_SCALE_FACTORS  e.g. "eDP-1=1.75;HDMI-1=1"
        val qtScreenFactors = env["QT_SCREEN_SCALE_FACTORS"]
        if (!qtScreenFactors.isNullOrBlank()) {
            val max = qtScreenFactors
                .split(";", ",")
                .mapNotNull { entry -> entry.trim().split("=").last().toFloatOrNull() }
                .maxOrNull()
            if (max != null) return ScaleDecision(max.coerceAtLeast(1.0f), "QT_SCREEN_SCALE_FACTORS")
        }

        // 3. QT_SCALE_FACTOR
        val qtScaleFactor = env["QT_SCALE_FACTOR"]?.toFloatOrNull()
        if (qtScaleFactor != null) {
            return ScaleDecision(qtScaleFactor.coerceAtLeast(1.0f), "QT_SCALE_FACTOR")
        }

        // 4. DRM connector modes heuristic
        val maxWidth = drmModes
            .mapNotNull { it.trim().substringBefore("x").toIntOrNull() }
            .maxOrNull() ?: 0

        if (maxWidth >= 3840) return ScaleDecision(2.0f, "drm.mode")
        if (maxWidth >= 2560) return ScaleDecision(1.5f, "drm.mode")

        return ScaleDecision(1.0f, "default")
    }

    private fun readDrmModes(): List<String> = runCatching {
        File("/sys/class/drm")
            .walkTopDown()
            .maxDepth(2)
            .filter { it.name == "modes" && it.isFile }
            .flatMap { it.readLines() }
            .toList()
    }.onFailure { log.debug("Could not read DRM modes: {}", it.message) }
        .getOrDefault(emptyList())
}
