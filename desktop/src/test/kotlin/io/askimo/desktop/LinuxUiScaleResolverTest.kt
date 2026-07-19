/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxUiScaleResolverTest {
    @Test
    fun `uses combined GTK scale when both vars are present`() {
        val decision = LinuxUiScaleResolver.resolve(
            env = mapOf("GDK_SCALE" to "2", "GDK_DPI_SCALE" to "0.75"),
            drmModes = emptyList(),
        )

        assertEquals(1.5f, decision.scale)
        assertEquals("GDK_SCALE*GDK_DPI_SCALE", decision.source)
    }

    @Test
    fun `uses exact qt screen scale factor`() {
        val decision = LinuxUiScaleResolver.resolve(
            env = mapOf("QT_SCREEN_SCALE_FACTORS" to "eDP-1=1.75;HDMI-1=1"),
            drmModes = emptyList(),
        )

        assertEquals(1.75f, decision.scale)
        assertEquals("QT_SCREEN_SCALE_FACTORS", decision.source)
    }

    @Test
    fun `falls back to 4k heuristic when no env scale is available`() {
        val decision = LinuxUiScaleResolver.resolve(
            env = emptyMap(),
            drmModes = listOf("3840x2160"),
        )

        assertEquals(2.0f, decision.scale)
        assertEquals("drm.mode", decision.source)
    }

    @Test
    fun `falls back to 1440p heuristic when no env scale is available`() {
        val decision = LinuxUiScaleResolver.resolve(
            env = emptyMap(),
            drmModes = listOf("2560x1440"),
        )

        assertEquals(1.5f, decision.scale)
        assertEquals("drm.mode", decision.source)
    }

    @Test
    fun `falls back to default scale when neither env nor drm modes are available`() {
        val decision = LinuxUiScaleResolver.resolve(
            env = emptyMap(),
            drmModes = emptyList(),
        )

        assertEquals(1.0f, decision.scale)
        assertEquals("default", decision.source)
    }

    @Test
    fun `auto scale is clamped to lower bound`() {
        val decision = LinuxUiScaleResolver.resolve(
            env = mapOf("QT_SCALE_FACTOR" to "0.5"),
            drmModes = emptyList(),
        )

        assertEquals(1.0f, decision.scale)
    }
}
