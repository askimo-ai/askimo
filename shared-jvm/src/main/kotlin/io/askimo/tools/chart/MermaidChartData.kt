/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.chart

import kotlinx.serialization.Serializable

/**
 * Data for Mermaid diagrams.
 * Supports all Mermaid diagram types: flowcharts, sequence diagrams, class diagrams,
 * state machines, ER diagrams, Gantt charts, pie charts, bar charts, line charts, etc.
 */
@Serializable
data class MermaidChartData(
    val title: String,
    val diagram: String,
    val theme: String = "default",
)
