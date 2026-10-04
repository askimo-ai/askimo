/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.chart

import dev.langchain4j.agent.tool.Tool
import io.askimo.core.util.JsonUtils.json
import io.askimo.tools.ToolResponseBuilder
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Tools for generating diagrams and visualizations using Mermaid.
 * Supports ALL Mermaid diagram types: flowcharts, sequence diagrams, class diagrams,
 * state machines, ER diagrams, Gantt charts, pie charts, bar/line charts (xychart-beta),
 * user journeys, git graphs, mind maps, requirement diagrams, treemaps, sankey diagrams,
 * block diagrams, packet diagrams, kanban boards, architecture diagrams, quadrant charts,
 * and C4 diagrams.
 *
 * NOTE: Radar/spider charts are not supported by Mermaid CLI.
 */
object ChartTools {
    private const val CLASS_NAME = "io.askimo.tools.chart.ChartTools"

    /**
     * Generate a Mermaid diagram for visualizations.
     *
     * @param title Diagram title
     * @param diagram Mermaid diagram definition
     * @param theme Theme name (default, dark, forest, neutral)
     * @return JSON response with diagram data
     */
    @Tool(
        """You are a Mermaid diagram generator. Use this tool ONLY when the user explicitly asks for a diagram, chart, graph, or visual representation.

OUTPUT FORMAT (MANDATORY):
```json
{
  "title": "<human-readable title>",
  "diagram": "<mermaid diagram as a single string with \\n line breaks>",
  "theme": "default"
}
```
Output MUST be valid JSON wrapped in a json code block. No text outside the block.

DIAGRAM TYPE SELECTION:
bar/line charts→xychart-beta | pie/proportions→pie | flowchart/process→graph TD or flowchart TD
sequence/UML→sequenceDiagram | class/UML→classDiagram | state machine→stateDiagram
ER diagrams→erDiagram | timelines→gantt | architecture→architecture-beta
system blocks→block-beta | data flow→sankey-beta | quadrant/matrix→quadrantChart
treemap/hierarchy→treemap | network packets→packet-beta | kanban→kanban
mind maps→mindmap | git workflows→gitGraph | user journeys→journey
C4 diagrams→C4Context
NOTE: Radar/spider charts are NOT supported — use xychart-beta bar chart instead.
NOTE: requirementDiagram is NOT supported by mmdc — use a flowchart or table description instead.

UNIVERSAL SYNTAX RULES (apply to all diagram types):
1. No colon after diagram keywords: write `graph TD` not `graph TD:`
2. No emojis anywhere in labels, node IDs, or titles
3. Titles: quoted for xychart-beta/quadrantChart (`title "My Chart"`), unquoted for gantt/pie/journey (`title My Chart`)
4. Node labels with special characters (colon, parentheses, slash, ampersand) MUST use double-quoted strings:
   BAD: A[Core Logic (Shared)] or B{Layer: UI}
   GOOD: A["Core Logic (Shared)"] or B{"Layer: UI"}
5. Edge/link labels (inside `|...|`) with special characters (parentheses, colons, slashes) MUST be quoted:
   BAD: -->|1. User Input (Query)| B
   GOOD: -->|"1. User Input (Query)"| B
6. subgraph titles MUST use ONLY the `id["Title"]` form — no unquoted text before the bracket:
   BAD: subgraph Core Logic / Business Services["Core Logic"]
   GOOD: subgraph CoreLogic["Core Logic / Business Services"]
7. Cylinder/database nodes use `[(Label)]` — label MUST be plain text, NOT a quoted string:
   BAD: F1[( "AI Provider API: OpenAI" )]
   GOOD: F1[(AI Provider API OpenAI)]
8. classDiagram stereotypes go INSIDE the class body: `<<interface>>` not on the class declaration line
9. ER diagram: every attribute needs a type; use valid cardinality (||--o{, }o--||)
10. Do NOT use `style`, `classDef`, or `class` styling directives — they are not supported and will cause parse errors
11. Comments MUST use `%%` (double percent) on their OWN line — never after a statement on the same line, and never single `%`:
   BAD:  % Data/Flow Connections
   BAD:  A --> B; %% some comment
   GOOD: %% Data/Flow Connections
   GOOD: %% some comment\n    A --> B;
12. subgraph IDs MUST be unique and MUST NOT match any node ID, AND edges inside a subgraph must NOT use the subgraph's own ID as source or target — all of these cause a cycle error:
   BAD:  subgraph D["Core Service Layer"] ... D -->|label| R1   (D is used as both subgraph ID and edge source inside it)
   BAD:  E_Models["label"] ... subgraph E_Models ... E_Models --> F1
   GOOD: subgraph ServiceLayer["Core Service Layer"]\n    SvcNode --> R1   (ServiceLayer ID never appears as a node or edge endpoint)
13. An edge can only carry ONE label — never chain two label syntaxes on the same edge:
   BAD:  A -- "label1" --> |"label2"| B
   GOOD: A -->|"label1"| B   or   A -- "label1" --> B

SPECIAL CHART SYNTAX (non-obvious rules):

xychart-beta — categories MUST be an array, count must match data points:
  x-axis ["Jan","Feb","Mar"] ✓   |   x-axis "Month" ✗
  y-axis "Revenue" 0 --> 1000
  bar [100, 200, 150]

architecture-beta — every node is `service id(icon)[Label]`; connections need side indicators:
  service api(cloud)[API Gateway]
  api:R --> L:web   (right-of-api → left-of-web)
  Icons: cloud, server, database, disk, internet (all optional)

sankey-beta — one flow per line, format: Source,Target,Value
  Sales,Online,500

packet-beta — bit-range fields: 0-15: "Source Port"

quadrantChart — axes use arrow ranges, points use [x,y] coordinates:
  x-axis "Low" --> "High"
  PointA: [0.3, 0.6]
        """,
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"generateMermaidDiagram\" }",
    )
    fun generateMermaidDiagram(
        title: String,
        diagram: String,
        theme: String = "default",
    ): String = try {
        require(diagram.isNotBlank()) { "Diagram definition cannot be empty" }

        val chartData = MermaidChartData(
            title = title,
            diagram = diagram,
            theme = theme,
        )

        val chartJson = json.encodeToJsonElement(chartData).jsonObject

        val diagramType = when {
            diagram.trim().startsWith("sequenceDiagram") -> "sequence"
            diagram.trim().startsWith("classDiagram") -> "class"
            diagram.trim().startsWith("stateDiagram") -> "state"
            diagram.trim().startsWith("erDiagram") -> "er"
            diagram.trim().startsWith("gantt") -> "gantt"
            diagram.trim().startsWith("pie") -> "pie"
            diagram.trim().startsWith("journey") -> "journey"
            diagram.trim().startsWith("treemap") -> "treemap"
            diagram.trim().startsWith("block") -> "block"
            diagram.trim().startsWith("architecture") -> "architecture"
            diagram.trim().startsWith("quadrantChart") -> "quadrant"
            diagram.trim().startsWith("packet") -> "packet"
            diagram.trim().startsWith("kanban") -> "kanban"
            diagram.trim().startsWith("sankey") -> "sankey"
            diagram.trim().startsWith("xychart") -> "chart"
            diagram.trim().startsWith("graph") || diagram.trim().startsWith("flowchart") -> "flowchart"
            else -> "diagram"
        }

        ToolResponseBuilder.successWithData(
            output = "Generated Mermaid $diagramType: '$title' (theme: $theme)",
            data = mapOf(
                "chartData" to chartJson.toString(),
                "type" to "mermaid",
                "diagramType" to diagramType,
                "theme" to theme,
            ),
        )
    } catch (e: Exception) {
        ToolResponseBuilder.failure(
            error = "Failed to generate Mermaid diagram: ${e.message}",
            metadata = mapOf(
                "title" to title,
                "exception" to (e::class.simpleName ?: "Exception"),
            ),
        )
    }
}
