/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.util

import io.askimo.core.logging.logger
import java.awt.Desktop
import java.io.File

private val log = logger<Platform>()

/**
 * Platform detection utilities for desktop application.
 */
object Platform {
    private val osName: String by lazy {
        System.getProperty("os.name") ?: ""
    }

    /**
     * Returns true if the current operating system is macOS.
     */
    val isMac: Boolean by lazy {
        osName.contains("Mac", ignoreCase = true)
    }

    /**
     * Returns true if the current operating system is Windows.
     */
    val isWindows: Boolean by lazy {
        osName.contains("Windows", ignoreCase = true)
    }

    /**
     * Returns true if the current operating system is Linux.
     */
    val isLinux: Boolean by lazy {
        osName.contains("Linux", ignoreCase = true)
    }

    /**
     * Returns the platform-specific modifier key symbol.
     * Returns "⌘" for macOS, "Ctrl" for other platforms.
     */
    val modifierKey: String by lazy {
        if (isMac) "⌘" else "Ctrl"
    }

    /**
     * Returns the platform-specific modifier key name.
     * Returns "Command" for macOS, "Ctrl" for other platforms.
     */
    val modifierKeyName: String by lazy {
        if (isMac) "Command" else "Ctrl"
    }

    /**
     * Opens a file or directory in the system's file manager (Finder/Explorer/Nautilus), shelling
     * out to the native `open`/`xdg-open`/`explorer` command per platform rather than relying
     * solely on [Desktop.open] (kept only as a last-resort fallback), since AWT's Desktop API has
     * historically been less reliable at this across all three platforms.
     */
    fun openInFileManager(file: File) {
        try {
            when {
                isMac -> Runtime.getRuntime().exec(arrayOf("open", file.absolutePath))
                isLinux -> Runtime.getRuntime().exec(arrayOf("xdg-open", file.absolutePath))
                isWindows -> Runtime.getRuntime().exec(arrayOf("explorer", file.absolutePath))
                else -> if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
            }
        } catch (e: Exception) {
            log.error("Failed to open in file manager: ${file.absolutePath}", e)
        }
    }
}
