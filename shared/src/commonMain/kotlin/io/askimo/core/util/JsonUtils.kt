/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import kotlinx.serialization.json.Json

/**
 * Global JSON configuration for consistent serialization/deserialization across the application.
 */
object JsonUtils {
    /**
     * Standard JSON instance with lenient parsing.
     * Use this for all JSON serialization/deserialization operations.
     *
     * Configuration:
     * - ignoreUnknownKeys: true - Skip unknown properties during deserialization
     * - prettyPrint: false - Compact output for efficiency
     * - encodeDefaults: true - Include default values in serialization
     * - isLenient: true - Accept non-standard JSON (e.g., unquoted strings)
     */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
        isLenient = true
    }

    /**
     * JSON instance with pretty printing enabled.
     * Use for human-readable output (e.g., config files, debug logs, exported data).
     */
    val prettyJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
    }
}
