/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent

import io.askimo.core.logging.logger

/**
 * Command for detecting follow-up opportunities from AI's response.
 * Determines which tools with FOLLOW_UP_BASED flag should be offered to the user.
 *
 * Uses pattern matching to detect data structures or content in the AI's
 * response that could benefit from tool usage (e.g., tabular data → chart).
 *
 * Note: This handles Stage 2 of the 2-stage flow. Tools with INTENT_BASED flag only
 * are handled by DetectUserIntentCommand (Stage 1).
 * Tools with BOTH flags are available in both stages.
 */
object DetectAiResponseIntentCommand {
    private val log = logger<DetectAiResponseIntentCommand>()

    /**
     * Execute the command to detect follow-up opportunities.
     *
     * @param aiResponse The AI's response text
     * @param availableTools Tools to check (should have FOLLOW_UP_BASED flag set)
     * @param mcpTools Optional MCP tools to include in suggestions (project-specific)
     * @return Follow-up suggestion or null if none found
     */
    fun execute(
        aiResponse: String,
        availableTools: List<ToolConfig>,
        mcpTools: List<ToolConfig> = emptyList(),
    ): FollowUpSuggestion? {
        // Combine built-in and MCP tools, filter only those with FOLLOW_UP_BASED flag
        val allTools = (availableTools + mcpTools).filter {
            (it.strategy and ToolStrategy.FOLLOW_UP_BASED) != 0
        }

        // Check if AI response contains data that could be visualized
        val chartableData = detectChartableData(aiResponse)
        if (chartableData != null) {
            val chartTool = allTools.find { it.category == ToolCategory.VISUALIZE }
            if (chartTool != null) {
                log.debug(
                    "Detected chartable {} data with confidence {}",
                    chartableData.dataType,
                    chartableData.confidence,
                )
                return FollowUpSuggestion(
                    tool = chartTool,
                    confidence = chartableData.confidence,
                    question = "📊 I found ${chartableData.dataType} data. Would you like me to create a chart?",
                    extractedData = chartableData.dataPoints.joinToString("\n") { "${it.label}: ${it.value}" },
                )
            }
        }

        return null
    }

    /**
     * Detects chartable data in AI response using pattern matching.
     * Looks for:
     * - Time series data (year: value)
     * - Tabular data (category: value)
     */
    private fun detectChartableData(text: String): ChartableData? {
        // Time series detection (year: value)
        val timeSeriesPattern = """(\d{4})\s*[:-]\s*[$€£¥]?([\d.,]+)\s*([KMBT]|trillion|billion|million)?""".toRegex()
        val matches = timeSeriesPattern.findAll(text)

        if (matches.count() >= 3) {
            val dataPoints = matches.map { match ->
                DataPoint(
                    label = match.groupValues[1],
                    value = parseValue(match.groupValues[2], match.groupValues[3]),
                )
            }.toList()

            return ChartableData(
                dataType = "time-series",
                dataPoints = dataPoints,
                confidence = 85,
            )
        }

        // Tabular data detection (category: value)
        val tablePattern = """^[\w\s]+:\s*[\d.,]+""".toRegex(RegexOption.MULTILINE)
        val tableMatches = tablePattern.findAll(text)

        if (tableMatches.count() >= 3) {
            val dataPoints = tableMatches.map { match ->
                val parts = match.value.split(":")
                DataPoint(
                    label = parts[0].trim(),
                    value = parts[1].trim().replace(",", "").toDoubleOrNull() ?: 0.0,
                )
            }.toList()

            return ChartableData(
                dataType = "categorical",
                dataPoints = dataPoints,
                confidence = 75,
            )
        }

        return null
    }

    /**
     * Parses numeric values with multiplier suffixes (K, M, B, T).
     */
    private fun parseValue(numStr: String, multiplier: String?): Double {
        val base = numStr.replace(",", "").toDoubleOrNull() ?: 0.0
        return when (multiplier?.lowercase()) {
            "k" -> base * 1_000
            "m", "million" -> base * 1_000_000
            "b", "billion" -> base * 1_000_000_000
            "t", "trillion" -> base * 1_000_000_000_000
            else -> base
        }
    }
}
