/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

/**
 * A single unit of AI work within a [PlanDef].
 *
 * A step is deliberately free of flow-control concerns — it only defines *what*
 * the AI should do, not *when* or *whether* it should run. Sequencing, parallelism
 * and conditions are expressed in the [WorkflowNode] tree.
 *
 * Both [system] and [message] may contain `{{variable}}` placeholders that are
 * resolved at execution time from user inputs and prior step outputs.
 *
 * @param id        Unique identifier within the plan. Also used as the output key
 *                  in `AgenticScope`, so other steps can reference this step's
 *                  result via `{{id}}`.
 * @param system    Optional system prompt injected before the user message.
 *                  Use this to give the step a persona or domain-specific instructions.
 * @param message   The user message sent to the AI. Must not be blank when this is a
 *                  regular AI step. Must be blank (or omitted) for interactive `ask` steps.
 * @param ask       Interactive question presented to the user at runtime.
 *                  When non-null this step pauses execution and waits for input — no AI call
 *                  is made. The answer is injected into scope under the step's [id].
 * @param tools     Tool identifiers from the ToolRegistry that this step may invoke.
 *                  Overrides the plan-level tool list when non-empty.
 */
data class PlanStep(
    val id: String,
    val system: String? = null,
    val message: String = "",
    val ask: String? = null,
    val tools: List<String> = emptyList(),
)
