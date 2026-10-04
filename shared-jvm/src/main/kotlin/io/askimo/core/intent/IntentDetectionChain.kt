/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent

import io.askimo.core.intent.detectors.CommunicationDetector
import io.askimo.core.intent.detectors.DatabaseDetector
import io.askimo.core.intent.detectors.ExecutionDetector
import io.askimo.core.intent.detectors.FileReadDetector
import io.askimo.core.intent.detectors.FileWriteDetector
import io.askimo.core.intent.detectors.MonitoringDetector
import io.askimo.core.intent.detectors.NetworkDetector
import io.askimo.core.intent.detectors.SearchDetector
import io.askimo.core.intent.detectors.TransformDetector
import io.askimo.core.intent.detectors.VersionControlDetector
import io.askimo.core.intent.detectors.VisualizationDetector
import io.askimo.core.intent.detectors.WeatherDetector
import io.askimo.core.intent.detectors.WebSearchDetector

/**
 * Chain of Responsibility pattern for intent detection.
 * Orchestrates multiple intent detectors to identify matching tool categories.
 */
class IntentDetectionChain(
    private val detectors: List<IntentDetector> = defaultDetectors,
) {
    /**
     * Detect all matching categories for the given message.
     *
     * @param message The user message (will be lowercased internally)
     * @return Set of matching tool categories
     */
    fun detectAll(message: String): Set<ToolCategory> {
        val lowerMessage = message.lowercase()

        return detectors
            .filter { it.detect(lowerMessage) }
            .map { it.category }
            .toSet()
    }

    /**
     * Get detector for a specific category (useful for testing and introspection).
     */
    fun getDetector(category: ToolCategory): IntentDetector? = detectors.find { it.category == category }

    /**
     * Get all registered detectors.
     */
    fun getAllDetectors(): List<IntentDetector> = detectors

    companion object {
        /**
         * Default detectors in order of registration.
         * Order doesn't matter for detection, but can be useful for debugging/display.
         */
        val defaultDetectors = listOf(
            VisualizationDetector(),
            FileWriteDetector(),
            FileReadDetector(),
            ExecutionDetector(),
            DatabaseDetector(),
            NetworkDetector(),
            SearchDetector(),
            WeatherDetector(),
            WebSearchDetector(),
            TransformDetector(),
            VersionControlDetector(),
            CommunicationDetector(),
            MonitoringDetector(),
        )
    }
}
