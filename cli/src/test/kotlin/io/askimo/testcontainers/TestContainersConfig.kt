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

        // Testcontainers only honors reuse.enable from ~/.testcontainers.properties,
        // not from a system property — write it so CI runners (and fresh machines) opt in too.
        val propsFile = java.io.File(System.getProperty("user.home"), ".testcontainers.properties")
        val alreadyEnabled = propsFile.exists() && propsFile.readText().contains("testcontainers.reuse.enable=true")
        if (!alreadyEnabled) {
            propsFile.appendText("testcontainers.reuse.enable=true\n")
        }

        configured = true
    }
}
