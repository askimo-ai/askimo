/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.config

/**
 * Feature flags for controlling feature availability across Askimo editions.
 *
 * Initialize at app startup via [FeatureFlags.initialize].
 */
data class FeatureFlagsConfig(
    val plansEnabled: Boolean = true,
    val skillsEnabled: Boolean = true,
    val projectsEnabled: Boolean = true,
    val discoverEnabled: Boolean = true,
    val mcpIntegrationEnabled: Boolean = true,
    val ragEnabled: Boolean = true,
)

/**
 * Singleton accessor for feature flags.
 */
object FeatureFlags {
    @Volatile
    private var config: FeatureFlagsConfig = FeatureFlagsConfig()

    /**
     * Initialize feature flags at app startup.
     * Call once from Main.kt before any UI is rendered.
     */
    fun initialize(config: FeatureFlagsConfig) {
        this.config = config
    }

    val plansEnabled: Boolean get() = config.plansEnabled
    val skillsEnabled: Boolean get() = config.skillsEnabled
    val projectsEnabled: Boolean get() = config.projectsEnabled
    val mcpIntegrationEnabled: Boolean get() = config.mcpIntegrationEnabled
    val ragEnabled: Boolean get() = config.ragEnabled
}
