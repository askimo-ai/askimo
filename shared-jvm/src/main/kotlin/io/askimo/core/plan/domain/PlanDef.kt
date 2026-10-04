/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

/**
 * Complete definition of an AI plan loaded from a YAML file.
 *
 * A [PlanDef] is immutable and provider-agnostic — it is pure declaration with no
 * dependency on LangChain4j or any AI runtime. The executor layer is responsible
 * for wiring it to models and tools at run time.
 *
 * Minimal single-step plan (no explicit workflow):
 * ```yaml
 * id: quick-summary
 * name: Quick Summary
 * inputs:
 *   - key: topic
 *     label: Topic
 *     required: true
 * steps:
 *   summarise:
 *     message: "Summarise {{topic}} in 3 bullet points."
 * workflow:
 *   type: step
 *   stepId: summarise
 * ```
 *
 * Multi-step plan with parallel execution:
 * ```yaml
 * id: travel-planner
 * name: Travel Planner
 * icon: "✈️"
 * inputs:
 *   - key: destination
 *     label: Destination
 *     required: true
 *   - key: include_flights
 *     label: Include flight search?
 *     type: toggle
 *     default: "true"
 * tools:
 *   - WEB_SEARCH
 * steps:
 *   assess:
 *     system: "You are a professional travel advisor."
 *     message: "Assess feasibility of a trip to {{destination}}."
 *   itinerary:
 *     message: "Create a day-by-day itinerary. Assessment: {{assess}}"
 *   flights:
 *     message: "Find best flights to {{destination}}."
 *   summary:
 *     message: "Combine into a travel report. Itinerary: {{itinerary}} Flights: {{flights}}"
 * workflow:
 *   type: sequence
 *   nodes:
 *     - type: step
 *       stepId: assess
 *     - type: parallel
 *       outputKey: research
 *       nodes:
 *         - type: step
 *           stepId: itinerary
 *         - type: conditional
 *           condition: "include_flights == true"
 *           node:
 *             type: step
 *             stepId: flights
 *     - type: step
 *       stepId: summary
 * ```
 *
 * @param id          Unique identifier — used as the file name base and database key.
 * @param name        Human-readable display name shown in the Plans sidebar.
 * @param icon        Emoji or icon identifier for the sidebar and gallery.
 * @param description Short description shown in the plan gallery.
 * @param inputs      Ordered list of user-facing input variables.
 * @param tools       Plan-level tool identifiers from the ToolRegistry.
 *                    Individual steps may declare additional tools via [PlanStep.tools].
 * @param steps       Map of step id → [PlanStep]. Referenced by [WorkflowNode.Step.stepId].
 * @param workflow    Root [WorkflowNode] defining execution topology.
 * @param builtIn     True for plans bundled with Askimo (read-only, not deletable).
 */
data class PlanDef(
    val id: String,
    val name: String,
    val icon: String = "",
    val description: String = "",
    val inputs: List<PlanInput> = emptyList(),
    val tools: List<String> = emptyList(),
    val steps: Map<String, PlanStep>,
    val workflow: WorkflowNode,
    val builtIn: Boolean = false,
)
