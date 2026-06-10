/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Represents the reasoning effort level for models that support extended reasoning capabilities.
 *
 * - LOW: Faster inference, lower cost, suitable for straightforward tasks
 * - MEDIUM: Balanced approach, moderate reasoning depth (default)
 * - HIGH: Thorough reasoning, higher cost, suitable for complex problem-solving
 */
enum class ReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
    ;

    companion object {
        val DEFAULT = MEDIUM
    }
}
