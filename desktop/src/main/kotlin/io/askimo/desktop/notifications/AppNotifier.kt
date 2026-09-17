/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.notifications

import io.askimo.core.logging.logger
import io.askimo.core.util.TextUtils
import io.askimo.ui.util.Platform
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Fire-and-forget OS-level notification banner (e.g. "chat response finished").
 *
 * Platform behavior:
 * - **macOS**: shells out to `osascript -e 'display notification ...'` to show a native
 *   Notification Center banner. [java.awt.TrayIcon.displayMessage] is unreliable on macOS
 *   (no consistent JDK bridge to the native notification framework across JDK/macOS versions).
 *
 * - **Linux**: shells out to `notify-send` (freedesktop.org notification spec, available on
 *   GNOME/KDE/XFCE/etc.) since [java.awt.SystemTray] is frequently unsupported (minimal DEs,
 *   WSL, some window managers), even when the desktop can still show notifications.
 *
 * - **Windows** (and Linux as a fallback if `notify-send` is unavailable): uses
 *   [java.awt.SystemTray] + a transient [TrayIcon], using Askimo's actual app icon.
 */
object AppNotifier {
    private val log = logger<AppNotifier>()

    /** Lazily created, reused across calls so we don't leak a tray icon per notification. */
    private val trayIcon: TrayIcon? by lazy { createTrayIconIfSupported() }

    /**
     * Shows an OS notification with the given [title] and [message].
     * Safe to call from any thread; never throws.
     */
    fun notify(title: String, message: String) {
        try {
            when {
                Platform.isMac -> notifyViaOsascript(title, message)
                Platform.isLinux -> notifyViaNotifySend(title, message)
                else -> notifyViaSystemTray(title, message)
            }
        } catch (e: Exception) {
            log.warn("Failed to show OS notification: {}", e.message)
        }
    }

    private fun notifyViaOsascript(title: String, message: String) {
        val script = "display notification ${TextUtils.quoteForAppleScript(message)} with title ${TextUtils.quoteForAppleScript(title)}"
        try {
            ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            log.warn("osascript unavailable, falling back to SystemTray: {}", e.message)
            notifyViaSystemTray(title, message)
        }
    }

    private fun notifyViaNotifySend(title: String, message: String) {
        try {
            ProcessBuilder("notify-send", "--app-name=Askimo", title, message)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            log.warn("notify-send unavailable, falling back to SystemTray: {}", e.message)
            notifyViaSystemTray(title, message)
        }
    }

    private fun notifyViaSystemTray(title: String, message: String) {
        val icon = trayIcon
        if (icon == null) {
            log.debug("SystemTray not supported on this platform -- skipping OS notification")
            return
        }
        icon.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    private fun createTrayIconIfSupported(): TrayIcon? {
        if (!SystemTray.isSupported()) return null
        return try {
            val icon = TrayIcon(loadAppIcon(), "Askimo")
            icon.isImageAutoSize = true
            SystemTray.getSystemTray().add(icon)
            icon
        } catch (e: Exception) {
            log.warn("Failed to initialize SystemTray icon: {}", e.message)
            null
        }
    }

    /**
     * Loads Askimo's real app icon so the notification banner (and menu-bar tray icon) shows
     * proper branding instead of a blank/generic image. Falls back to a transparent placeholder
     * if the resource can't be read for any reason -- [TrayIcon] requires a non-null image.
     */
    private fun loadAppIcon(): BufferedImage = runCatching {
        object {}.javaClass.getResourceAsStream("/images/askimo_512.png")?.use { ImageIO.read(it) }
    }.getOrNull() ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
}
