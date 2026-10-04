/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Represents the reasoning effort level for models that support extended reasoning capabilities.
 *
 * - OFF: Disables extended reasoning entirely (model responds without deep thinking)
 * - LOW: Faster inference, lower cost, suitable for straightforward tasks
 * - MEDIUM: Balanced approach, moderate reasoning depth (default)
 * - HIGH: Thorough reasoning, higher cost, suitable for complex problem-solving
 */
enum class ReasoningEffort(val value: String) {
    OFF("off"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    /** Returns true when thinking/reasoning should be sent to the model. */
    val isEnabled: Boolean get() = this != OFF

    companion object {
        val DEFAULT = MEDIUM

        fun fromValue(value: String): ReasoningEffort = entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}
