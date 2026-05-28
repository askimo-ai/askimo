/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.i18n.LocalizationManager
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder

/**
 * Share targets supported by Askimo.
 */
enum class ShareTarget { X, LINKEDIN, FACEBOOK, HACKER_NEWS }

/**
 * Common share utilities
 */
object ShareUtils {
    fun shareText(): String = LocalizationManager.getString("menu.help.share.text")

    fun labelFor(target: ShareTarget): String = when (target) {
        ShareTarget.X -> LocalizationManager.getString("menu.help.share.x")
        ShareTarget.LINKEDIN -> LocalizationManager.getString("menu.help.share.linkedin")
        ShareTarget.FACEBOOK -> LocalizationManager.getString("menu.help.share.facebook")
        ShareTarget.HACKER_NEWS -> LocalizationManager.getString("menu.help.share.hackernews")
    }

    /** Opens the browser to share on the given target. Silently ignores failures. */
    fun share(target: ShareTarget) {
        runCatching {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            val encoded = URLEncoder.encode(shareText(), "UTF-8")
            val url = URLEncoder.encode("https://$DOMAIN", "UTF-8")
            val uri = when (target) {
                ShareTarget.X -> "https://x.com/intent/tweet?text=$encoded"
                ShareTarget.LINKEDIN -> "https://www.linkedin.com/sharing/share-offsite/?url=$url"
                ShareTarget.FACEBOOK -> "https://www.facebook.com/sharer/sharer.php?u=$url"
                ShareTarget.HACKER_NEWS -> "https://news.ycombinator.com/submitlink?u=$url"
            }
            desktop.browse(URI(uri))
        }
    }
}
