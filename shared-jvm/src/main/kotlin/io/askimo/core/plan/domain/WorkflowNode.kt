/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Describes the execution topology of a plan as a tree of nodes.
 *
 * This is a pure data structure — it carries no LangChain4j types so it can be
 * serialised to/from YAML and stored independently of the AI runtime.
 *
 * The tree is evaluated recursively at execution time by `PlanWorkflowBuilder`,
 * which maps each node to the corresponding `AgenticServices` builder:
 *
 * - [Step]        → a single `UntypedAgent` wrapping one [PlanStep]
 * - [Ask]         → interactive pause: user is prompted for input, answer stored in scope
 * - [Sequence]    → `AgenticServices.sequenceBuilder()`
 * - [Parallel]    → `AgenticServices.parallelBuilder()`
 * - [Conditional] → `AgenticServices.conditionalBuilder()`
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = WorkflowNode.Step::class, name = "step"),
    JsonSubTypes.Type(value = WorkflowNode.Ask::class, name = "ask"),
    JsonSubTypes.Type(value = WorkflowNode.Sequence::class, name = "sequence"),
    JsonSubTypes.Type(value = WorkflowNode.Parallel::class, name = "parallel"),
    JsonSubTypes.Type(value = WorkflowNode.Conditional::class, name = "conditional"),
)
sealed class WorkflowNode {

    /**
     * Leaf node — references a [PlanStep] by its id.
     *
     * @param stepId Must match a key in `PlanDef.steps`.
     */
    data class Step(val stepId: String) : WorkflowNode()

    /**
     * Interactive leaf node — pauses execution and asks the user a question.
     *
     * The user's answer is injected into the AgenticScope under [stepId] so subsequent
     * steps can reference it via `{{stepId}}` in their message templates.
     *
     * YAML example:
     * ```yaml
     * - type: ask
     *   stepId: tone_preference
     * ```
     * (The question text lives in the corresponding [PlanStep.ask] field.)
     *
     * @param stepId Must match a key in `PlanDef.steps` that has a non-blank [PlanStep.ask].
     */
    data class Ask(val stepId: String) : WorkflowNode()

    /**
     * Executes child nodes one after another.
     * The output of each child is written to `AgenticScope` before the next runs.
     */
    data class Sequence(val nodes: List<WorkflowNode>) : WorkflowNode()

    /**
     * Executes all child nodes concurrently.
     *
     * @param outputKey The key under which the merged output is stored in `AgenticScope`.
     */
    data class Parallel(
        val nodes: List<WorkflowNode>,
        val outputKey: String = "parallel_result",
    ) : WorkflowNode()

    /**
     * Executes [node] only when [condition] evaluates to `true`.
     *
     * Condition syntax (evaluated at runtime against `AgenticScope`):
     * - `"key == value"`       — exact match against an input or prior step output
     * - `"key == true"`        — boolean toggle check
     * - `"key contains text"`  — substring check
     *
     * @param condition Simple expression string evaluated at runtime.
     * @param node      The child node to execute when condition is true.
     */
    data class Conditional(
        val condition: String,
        val node: WorkflowNode,
    ) : WorkflowNode()
}
