/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import io.askimo.core.util.ProcessBuilderExt

/**
 * Detects if macOS is in dark mode by querying system defaults.
 * This is more reliable than AWT properties which often return null.
 */
fun detectMacOSDarkMode(): Boolean {
    return try {
        val osName = System.getProperty("os.name")
        if (!osName.contains("Mac", ignoreCase = true)) {
            return false
        }

        val process =
            ProcessBuilderExt(
                "defaults",
                "read",
                "-g",
                "AppleInterfaceStyle",
            ).start()

        val result = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        // If the command succeeds and returns "Dark", we're in dark mode
        // If the command fails (exit code != 0), the key doesn't exist, meaning light mode
        exitCode == 0 && result.equals("Dark", ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}

/** Controls whether the app uses a light, dark, or system-matched base surface. */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}
