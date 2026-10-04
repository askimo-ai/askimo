/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent

/**
 * Result of intent detection from user input.
 */
data class IntentDetectionResult(
    val stage: IntentStage,
    val tools: List<ToolConfig>,
    val confidence: Int,
    val reasoning: String,
)

/**
 * Stage at which intent was detected.
 */
enum class IntentStage {
    /**
     * Intent detected from user's input message (before AI response).
     */
    USER_INPUT,

    /**
     * Intent detected from AI's response (after AI response).
     */
    AI_RESPONSE,
}

/**
 * Suggestion for follow-up action based on AI response.
 */
data class FollowUpSuggestion(
    val tool: ToolConfig,
    val confidence: Int,
    val question: String,
    val extractedData: String? = null,
)

/**
 * Detected chartable data in AI response.
 */
data class ChartableData(
    val dataType: String,
    val dataPoints: List<DataPoint>,
    val confidence: Int,
)

/**
 * A data point for chart visualization.
 */
data class DataPoint(
    val label: String,
    val value: Double,
)
