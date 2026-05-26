/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.logging.logger
import io.askimo.core.service.UpdateInfo
import io.askimo.ui.common.preferences.AccountPreferences
import io.askimo.ui.service.UpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/**
 * ViewModel for managing update check operations.
 */
class UpdateViewModel(
    private val scope: CoroutineScope,
    private val updateService: UpdateService = UpdateService(),
) {
    private val log = logger<UpdateViewModel>()

    var isChecking by mutableStateOf(false)
        private set

    var releaseInfo by mutableStateOf<UpdateInfo?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var showUpdateDialog by mutableStateOf(false)
        private set

    /**
     * Check for updates in the background.
     * If [silent] is true, only the notification badge/popup appears — no blocking dialog.
     * If [silent] is false (manual "Check for Updates"), the dialog is always shown.
     */
    fun checkForUpdates(silent: Boolean = false) {
        if (isChecking) return

        isChecking = true
        errorMessage = null
        releaseInfo = null

        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    updateService.checkForUpdates()
                }

                releaseInfo = info

                if (info != null) {
                    if (!silent) {
                        // Manual check: always show the dialog regardless of version
                        showUpdateDialog = true
                    }
                    // Silent auto-check: UpdateAvailableEvent is emitted by UpdateService
                    // which causes notificationIcon to auto-open the notification popup.
                } else {
                    if (!silent) {
                        errorMessage = "Failed to check for updates"
                        showUpdateDialog = true
                    }
                }
            } catch (e: Exception) {
                log.error("Error checking for updates", e)
                if (!silent) {
                    errorMessage = "Failed to check for updates: ${e.message}"
                }
            } finally {
                isChecking = false
            }
        }
    }

    fun dismissUpdateDialog() {
        showUpdateDialog = false
    }

    /**
     * Dismiss the update dialog AND mark this version as skipped so the
     * auto-popup never appears again for this specific release.
     */
    fun skipThisVersion() {
        releaseInfo?.latestVersion?.let { AccountPreferences.device().setDismissedUpdateVersion(it) }
        showUpdateDialog = false
    }

    /**
     * Show update dialog for existing release info without re-checking.
     * Used when user clicks Details button in notification.
     */
    fun showUpdateDialogForExistingRelease() {
        if (releaseInfo != null && releaseInfo!!.isNewVersion) {
            showUpdateDialog = true
        }
    }

    fun openHowToUpdatePage() {
        runCatching { Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/updating/")) }
            .onFailure { log.error("Failed to open how-to-update page", it) }
    }

    fun openDownloadPage() {
        releaseInfo?.let { info ->
            runCatching { Desktop.getDesktop().browse(URI(info.downloadUrl)) }
                .onFailure { log.error("Failed to open download page", it) }
        }
    }

    fun getCurrentVersion(): String = updateService.getCurrentVersion()
}
