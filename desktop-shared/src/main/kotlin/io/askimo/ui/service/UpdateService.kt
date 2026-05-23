/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.service

import io.askimo.core.VersionInfo
import io.askimo.core.event.EventBus
import io.askimo.core.event.system.UpdateAvailableEvent
import io.askimo.core.logging.logger
import io.askimo.core.service.UpdateChecker
import io.askimo.core.service.UpdateInfo

/**
 * Desktop-specific service to check for application updates.
 * Wraps the core UpdateChecker and emits EventBus events for the desktop UI.
 *
 * @param githubRepo The GitHub repository to check for releases
 */
class UpdateService {
    private val log = logger<UpdateService>()
    private val updateChecker = UpdateChecker(
        userAgent = "Askimo-Desktop/${VersionInfo.version}",
    )

    // Track the last version for which we emitted an event to prevent duplicates
    private var lastEmittedVersion: String? = null

    /**
     * Check for updates and emit an event if a new version is available.
     * Returns the update information or null if the check fails.
     */
    fun checkForUpdates(): UpdateInfo? {
        val updateInfo = updateChecker.checkForUpdates() ?: return null

        // Emit event for desktop UI if new version is available
        if (updateInfo.isNewVersion && lastEmittedVersion != updateInfo.latestVersion) {
            log.info("New version available: ${updateInfo.latestVersion} (current: ${updateInfo.currentVersion})")
            EventBus.post(
                UpdateAvailableEvent(
                    currentVersion = updateInfo.currentVersion,
                    latestVersion = updateInfo.latestVersion,
                    releaseNotes = updateInfo.releaseNotes,
                    downloadUrl = updateInfo.downloadUrl,
                ),
            )
            lastEmittedVersion = updateInfo.latestVersion
        }

        return updateInfo
    }

    fun getCurrentVersion(): String = UpdateChecker.getCurrentVersion()
}
