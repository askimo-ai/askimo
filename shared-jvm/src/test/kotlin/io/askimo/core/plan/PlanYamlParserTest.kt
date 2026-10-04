/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import io.askimo.core.plan.domain.WorkflowNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanYamlParserTest {

    // ── Minimal single-step plan ──────────────────────────────────────────────

    @Test
    fun `parses minimal single-step plan without explicit workflow`() {
        val yaml = """
            id: quick-summary
            name: Quick Summary
            steps:
              summarise:
                message: "Summarise {{topic}} in 3 bullet points."
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        assertEquals("quick-summary", plan.id)
        assertEquals("Quick Summary", plan.name)
        assertEquals(1, plan.steps.size)
        assertEquals("Summarise {{topic}} in 3 bullet points.", plan.steps["summarise"]?.message)
        // Default workflow: single Step node
        val workflow = plan.workflow
        assertTrue(workflow is WorkflowNode.Step)
        assertEquals("summarise", (workflow as WorkflowNode.Step).stepId)
    }

    // ── Inputs ────────────────────────────────────────────────────────────────

    @Test
    fun `parses inputs with all fields`() {
        val yaml = """
            id: travel-planner
            name: Travel Planner
            icon: "✈️"
            description: Plan a perfect trip
            inputs:
              - key: destination
                label: Destination
                type: text
                required: true
                hint: e.g. Japan
              - key: duration
                label: Duration
                type: select
                options: ["1 week", "2 weeks"]
                default: "1 week"
              - key: include_flights
                label: Include flights?
                type: toggle
                default: "true"
            steps:
              assess:
                message: "Assess {{destination}}"
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        assertEquals("✈️", plan.icon)
        assertEquals("Plan a perfect trip", plan.description)
        assertEquals(3, plan.inputs.size)

        val dest = plan.inputs[0]
        assertEquals("destination", dest.key)
        assertEquals("text", dest.type)
        assertTrue(dest.required)
        assertEquals("e.g. Japan", dest.hint)

        val duration = plan.inputs[1]
        assertEquals("select", duration.type)
        assertEquals(listOf("1 week", "2 weeks"), duration.options)
        assertEquals("1 week", duration.default)
    }

    // ── Workflow: sequence ────────────────────────────────────────────────────

    @Test
    fun `parses explicit sequence workflow`() {
        val yaml = """
            id: report
            name: Report
            steps:
              research:
                message: "Research {{topic}}"
              write:
                message: "Write report. Research: {{research}}"
            workflow:
              type: sequence
              nodes:
                - type: step
                  stepId: research
                - type: step
                  stepId: write
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        val workflow = plan.workflow as WorkflowNode.Sequence
        assertEquals(2, workflow.nodes.size)
        assertEquals("research", (workflow.nodes[0] as WorkflowNode.Step).stepId)
        assertEquals("write", (workflow.nodes[1] as WorkflowNode.Step).stepId)
    }

    // ── Workflow: parallel ────────────────────────────────────────────────────

    @Test
    fun `parses parallel node`() {
        val yaml = """
            id: multi
            name: Multi
            steps:
              a:
                message: "Task A"
              b:
                message: "Task B"
              summary:
                message: "Summary: {{a}} {{b}}"
            workflow:
              type: sequence
              nodes:
                - type: parallel
                  outputKey: research
                  nodes:
                    - type: step
                      stepId: a
                    - type: step
                      stepId: b
                - type: step
                  stepId: summary
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        val seq = plan.workflow as WorkflowNode.Sequence
        val parallel = seq.nodes[0] as WorkflowNode.Parallel
        assertEquals("research", parallel.outputKey)
        assertEquals(2, parallel.nodes.size)
    }

    // ── Workflow: conditional ─────────────────────────────────────────────────

    @Test
    fun `parses conditional node`() {
        val yaml = """
            id: travel-planner
            name: Travel Planner
            inputs:
              - key: include_flights
                label: Include flights?
                type: toggle
                default: "true"
            steps:
              itinerary:
                message: "Create itinerary"
              flights:
                message: "Find flights"
            workflow:
              type: sequence
              nodes:
                - type: step
                  stepId: itinerary
                - type: conditional
                  condition: "include_flights == true"
                  node:
                    type: step
                    stepId: flights
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        val seq = plan.workflow as WorkflowNode.Sequence
        val conditional = seq.nodes[1] as WorkflowNode.Conditional
        assertEquals("include_flights == true", conditional.condition)
        assertEquals("flights", (conditional.node as WorkflowNode.Step).stepId)
    }

    // ── Step fields ───────────────────────────────────────────────────────────

    @Test
    fun `parses step with system prompt and tools`() {
        val yaml = """
            id: advisor
            name: Advisor
            steps:
              advise:
                system: "You are a financial advisor."
                message: "Analyse {{ticker}}"
                tools:
                  - WEB_SEARCH
                  - CALCULATOR
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)
        val step = plan.steps["advise"]!!

        assertEquals("You are a financial advisor.", step.system)
        assertEquals("Analyse {{ticker}}", step.message)
        assertEquals(listOf("WEB_SEARCH", "CALCULATOR"), step.tools)
    }

    @Test
    fun `step without system prompt has null system`() {
        val yaml = """
            id: simple
            name: Simple
            steps:
              do_it:
                message: "Just do it"
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)
        assertNull(plan.steps["do_it"]?.system)
    }

    // ── List-style steps (inline sequential) ─────────────────────────────────

    @Test
    fun `parses list-style steps and infers sequential workflow`() {
        val yaml = """
            id: report
            name: Report
            steps:
              - id: research
                message: "Research {{topic}}"
              - id: write
                message: "Write report. Research: {{research}}"
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        assertEquals(2, plan.steps.size)
        assertEquals("Research {{topic}}", plan.steps["research"]?.message)
        assertEquals("Write report. Research: {{research}}", plan.steps["write"]?.message)

        // Workflow inferred as Sequence in declaration order
        val workflow = plan.workflow as WorkflowNode.Sequence
        assertEquals(2, workflow.nodes.size)
        assertEquals("research", (workflow.nodes[0] as WorkflowNode.Step).stepId)
        assertEquals("write", (workflow.nodes[1] as WorkflowNode.Step).stepId)
    }

    @Test
    fun `list-style single step infers Step workflow`() {
        val yaml = """
            id: quick
            name: Quick
            steps:
              - id: summarise
                message: "Summarise {{topic}}"
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        assertTrue(plan.workflow is WorkflowNode.Step)
        assertEquals("summarise", (plan.workflow as WorkflowNode.Step).stepId)
    }

    @Test
    fun `list-style steps preserve declaration order`() {
        val yaml = """
            id: ordered
            name: Ordered
            steps:
              - id: first
                message: "First"
              - id: second
                message: "Second"
              - id: third
                message: "Third"
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        val seq = plan.workflow as WorkflowNode.Sequence
        assertEquals("first", (seq.nodes[0] as WorkflowNode.Step).stepId)
        assertEquals("second", (seq.nodes[1] as WorkflowNode.Step).stepId)
        assertEquals("third", (seq.nodes[2] as WorkflowNode.Step).stepId)
    }

    @Test
    fun `list-style steps with system prompt and tools`() {
        val yaml = """
            id: advisor
            name: Advisor
            steps:
              - id: advise
                system: "You are a financial advisor."
                message: "Analyse {{ticker}}"
                tools:
                  - WEB_SEARCH
                  - CALCULATOR
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)
        val step = plan.steps["advise"]!!

        assertEquals("You are a financial advisor.", step.system)
        assertEquals("Analyse {{ticker}}", step.message)
        assertEquals(listOf("WEB_SEARCH", "CALCULATOR"), step.tools)
    }

    @Test
    fun `list-style steps with explicit workflow block — workflow wins`() {
        val yaml = """
            id: travel-planner
            name: Travel Planner
            steps:
              - id: itinerary
                message: "Create itinerary"
              - id: flights
                message: "Find flights"
            workflow:
              type: sequence
              nodes:
                - type: step
                  stepId: flights
                - type: step
                  stepId: itinerary
        """.trimIndent()

        val plan = PlanYamlParser.parse(yaml)

        // Workflow block overrides list order
        val seq = plan.workflow as WorkflowNode.Sequence
        assertEquals("flights", (seq.nodes[0] as WorkflowNode.Step).stepId)
        assertEquals("itinerary", (seq.nodes[1] as WorkflowNode.Step).stepId)
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `validate returns null for valid yaml`() {
        val yaml = """
            id: ok
            name: OK Plan
            steps:
              run:
                message: "Run {{task}}"
        """.trimIndent()

        assertNull(PlanYamlParser.validate(yaml))
    }

    @Test
    fun `validate returns error for missing id`() {
        val yaml = """
            name: No ID Plan
            steps:
              run:
                message: "Run"
        """.trimIndent()

        assertNotNull(PlanYamlParser.validate(yaml))
        assertTrue(PlanYamlParser.validate(yaml)!!.contains("id"))
    }

    @Test
    fun `validate returns error for unknown step reference in workflow`() {
        val yaml = """
            id: bad
            name: Bad
            steps:
              real_step:
                message: "Real"
            workflow:
              type: step
              stepId: ghost_step
        """.trimIndent()

        val error = PlanYamlParser.validate(yaml)
        assertNotNull(error)
        assertTrue(error!!.contains("ghost_step"))
    }
}
