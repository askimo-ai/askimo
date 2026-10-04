/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent.detectors

import io.askimo.core.intent.BaseIntentDetector
import io.askimo.core.intent.ToolCategory

/**
 * Detector for visualization/charting intent.
 */
class VisualizationDetector :
    BaseIntentDetector(
        category = ToolCategory.VISUALIZE,
        directKeywords = listOf(
            "chart",
            "graph",
            "plot",
            "visualize",
            "visualization",
            "diagram",
            "draw",
        ),
        contextualPatterns = listOf(
            "\\bdisplay\\b.*\\bchart\\b", "\\bdisplay\\b.*\\bgraph\\b", "\\bdisplay\\b.*\\bplot\\b",
            "\\bshow\\b.*\\bchart\\b", "\\bshow\\b.*\\bgraph\\b", "\\bshow\\b.*\\bplot\\b",
            "\\bpresent\\b.*\\bchart\\b", "\\bpresent\\b.*\\bgraph\\b",
            "\\brender\\b.*\\bchart\\b", "\\brender\\b.*\\bgraph\\b",
            "\\bin\\b.*\\bchart\\b", "\\bin\\b.*\\bgraph\\b", "\\bin\\b.*\\bplot\\b",
            "\\bas\\b.*\\bchart\\b", "\\bas\\b.*\\bgraph\\b", "\\bas\\b.*\\bplot\\b",
            "\\bwith\\b.*\\bchart\\b", "\\bwith\\b.*\\bgraph\\b",
            "\\busing\\b.*\\bchart\\b", "\\busing\\b.*\\bgraph\\b",
            "\\bcreate\\b.*\\bchart\\b", "\\bcreate\\b.*\\bgraph\\b",
            "\\bgenerate\\b.*\\bchart\\b", "\\bgenerate\\b.*\\bgraph\\b",
            "\\bmake\\b.*\\bchart\\b", "\\bmake\\b.*\\bgraph\\b",
        ),
    )
