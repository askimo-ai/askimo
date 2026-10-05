/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import io.askimo.core.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages developer mode preferences for the desktop application.
 *
 * Developer mode has two levels:
 * 1. enabled: Whether developer mode features are available (configured in AppConfig)
 * 2. active: Whether developer mode is currently turned on (persisted in AppConfig)
 *
 * The UI only shows the developer mode toggle if it's enabled in the config.
 */
object DeveloperModePreferences {
    private val _isActive = MutableStateFlow(AppConfig.developer.active)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /**
     * Check if developer mode is enabled in the configuration.
     * This controls whether the developer mode UI is shown.
     */
    fun isEnabled(): Boolean = AppConfig.developer.enabled

    /**
     * Set developer mode active state and persist to config file.
     */
    fun setActive(active: Boolean) {
        if (!isEnabled()) {
            return
        }

        _isActive.value = active

        // Persist to config file using generic updateField method
        AppConfig.updateField("developer.active", active)
    }
}
