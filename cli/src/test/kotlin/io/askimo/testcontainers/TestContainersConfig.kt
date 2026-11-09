/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.testcontainers

/**
 * Common configuration for all TestContainers.
 * This initializes shared system properties that apply to all containers.
 */
object TestContainersConfig {
    private var configured = false

    /**
     * Configure common TestContainers system properties.
     * This is idempotent - safe to call multiple times.
     */
    @Synchronized
    fun ensureConfigured() {
        if (configured) return

        System.setProperty("api.version", "1.44")

        configured = true
    }
}
