/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import dev.langchain4j.agentic.AgenticServices
import dev.langchain4j.agentic.UntypedAgent
import dev.langchain4j.model.chat.ChatModel
import io.askimo.core.analytics.Analytics
import io.askimo.core.analytics.AnalyticsEvent
import io.askimo.core.event.EventBus
import io.askimo.core.logging.logger
import io.askimo.core.plan.domain.PlanDef
import io.askimo.core.plan.domain.PlanStep
import io.askimo.core.plan.domain.PlanStepOutput
import io.askimo.core.plan.domain.WorkflowNode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Executes a [PlanDef] against a [ChatModel] using the LangChain4j Agentic API.
 *
 * ## How it works
 *
 * 1. Caller provides user-supplied input values (matching [PlanDef.inputs]).
 * 2. The executor walks the [WorkflowNode] tree and maps each node to the
 *    corresponding `AgenticServices` builder:
 *    - [WorkflowNode.Step]        → `AgenticServices.agentBuilder()` (one `UntypedAgent` per step)
 *    - [WorkflowNode.Sequence]    → `AgenticServices.sequenceBuilder()`
 *    - [WorkflowNode.Parallel]    → `AgenticServices.parallelBuilder()`
 *    - [WorkflowNode.Conditional] → `AgenticServices.conditionalBuilder()`
 * 3. Inputs are seeded into the shared `AgenticScope` via `UntypedAgent.invoke(Map)`.
 *    Each step reads what it needs from scope (prior outputs resolve `{{key}}` in the
 *    LangChain4j user-message template) and writes its own result back under `outputKey = stepId`.
 * 4. The final output is read from scope using the last step's id (or plan id as fallback).
 *
 * ## Tools
 * Each step receives a [PlanToolProvider] populated with its *effective* tool list:
 * - If [PlanStep.tools] is non-empty it is used as-is (step-level override).
 * - Otherwise [PlanDef.tools] is used as the plan-wide default.
 * - A step with no tools at either level runs without any tools attached.
 *
 * Only built-in [ToolRegistry] tools are resolved at this layer.
 * MCP tools are skipped until a plan-aware MCP wiring layer is added.
 */
class PlanExecutor(private val chatModel: ChatModel) {

    private val log = logger<PlanExecutor>()

    /**
     * Executes the plan and returns the final AI-generated result as a string.
     *
     * @param plan        The parsed plan definition.
     * @param inputs      User-provided values keyed by [PlanInput.key].
     * @param executionId Optional [PlanExecution] record id — passed to [PlanExecutionListener]
     *                    so every [PlanStepEvent] is correlated back to the DB record.
     * @param onStepOutputs Optional callback invoked after execution with the ordered list of
     *                      completed step outputs (including nullable usage metadata).
     * @return The final output string produced by the last step in the workflow.
     */
    fun execute(
        plan: PlanDef,
        inputs: Map<String, String>,
        executionId: String = "",
        onStepOutputs: ((List<PlanStepOutput>) -> Unit)? = null,
    ): String {
        validateInputs(plan, inputs)

        val stepCount = plan.steps.size
        val hasParallel = containsNode<WorkflowNode.Parallel>(plan.workflow)
        val hasConditional = containsNode<WorkflowNode.Conditional>(plan.workflow)
        val hasInteractive = containsNode<WorkflowNode.Ask>(plan.workflow)
        val toolCount = plan.tools.size + plan.steps.values.sumOf { it.tools.size }

        Analytics.track(
            AnalyticsEvent.PLAN_STARTED,
            mapOf(
                "step_count" to stepCount.toString(),
                "has_parallel" to hasParallel.toString(),
                "has_conditional" to hasConditional.toString(),
                "has_interactive" to hasInteractive.toString(),
                "tool_count" to toolCount.toString(),
            ),
        )

        val startMs = System.currentTimeMillis()

        return try {
            val output = executeInternal(plan, inputs, executionId, onStepOutputs)
            val durationMs = System.currentTimeMillis() - startMs
            val durationBucket = when {
                durationMs < 5_000 -> "<5s"
                durationMs < 30_000 -> "5-30s"
                durationMs < 120_000 -> "30-120s"
                else -> ">120s"
            }
            val outputBucket = when {
                output.length < 500 -> "<500"
                output.length < 2000 -> "500-2000"
                else -> ">2000"
            }
            Analytics.track(
                AnalyticsEvent.PLAN_COMPLETED,
                mapOf(
                    "step_count" to stepCount.toString(),
                    "duration_bucket" to durationBucket,
                    "output_length_bucket" to outputBucket,
                ),
            )
            output
        } catch (e: IllegalArgumentException) {
            Analytics.track(
                AnalyticsEvent.PLAN_FAILED,
                mapOf("step_count" to stepCount.toString(), "error_type" to "missing_input"),
            )
            throw e
        } catch (e: Exception) {
            Analytics.track(
                AnalyticsEvent.PLAN_FAILED,
                mapOf("step_count" to stepCount.toString(), "error_type" to "step_failed"),
            )
            throw e
        }
    }

    private fun executeInternal(
        plan: PlanDef,
        inputs: Map<String, String>,
        executionId: String,
        onStepOutputs: ((List<PlanStepOutput>) -> Unit)? = null,
    ): String {
        log.debug("Executing plan '{}' (execution={}) with inputs: {}", plan.id, executionId, inputs.keys)

        // Collect interactive answers first by traversing the workflow for Ask nodes.
        // This populates a mutable scope map that is seeded into the agentic run.
        val scopeInputs: MutableMap<String, Any> = inputs.toMutableMap()

        // Resolve file/folder inputs: replace the path value with actual file content.
        resolveFileInputs(plan, scopeInputs)

        collectInteractiveInputs(plan.workflow, plan.steps, plan, executionId, scopeInputs)

        // Build the root agent with the observability listener attached.
        val listener = PlanExecutionListener(planId = plan.id, executionId = executionId)
        val rootAgent: UntypedAgent? = buildAgent(plan.workflow, plan.steps, plan, listener)

        rootAgent?.invoke(scopeInputs)

        val lastStepId = lastLeafStepId(plan.workflow)
        val output = listener.stepOutputs.lastOrNull { it.stepName == lastStepId }?.output
            ?: listener.stepOutputs.lastOrNull()?.output
            ?: ""

        logExecutionSummary(plan, output)

        onStepOutputs?.invoke(listener.stepOutputs.toList())

        return output
    }

    /**
     * Walks the workflow tree in execution order and, for every [WorkflowNode.Ask] node,
     * emits a [PlanStepEvent.WaitingForInput] event and blocks until the user answers.
     * Answers are written directly into [scope] so the agentic run can reference them.
     */
    private fun collectInteractiveInputs(
        node: WorkflowNode,
        steps: Map<String, PlanStep>,
        plan: PlanDef,
        executionId: String,
        scope: MutableMap<String, Any>,
    ) {
        when (node) {
            is WorkflowNode.Ask -> {
                val step = steps[node.stepId]
                    ?: error("Plan references unknown ask step '${node.stepId}'")
                val question = step.ask?.takeIf { it.isNotBlank() }
                    ?: error("Step '${node.stepId}' is referenced as an ask node but has no 'ask' field")

                val channel = PlanInputChannel()

                log.debug("[{}] Interactive step '{}' — waiting for user input", plan.id, node.stepId)

                runBlocking {
                    EventBus.post(
                        PlanStepEvent.WaitingForInput(
                            planId = plan.id,
                            stepName = node.stepId,
                            executionId = executionId,
                            question = question,
                            inputKey = node.stepId,
                            channel = channel,
                        ),
                    )
                }

                val answer = channel.waitForAnswer()
                scope[node.stepId] = answer.ifBlank { "(no answer provided)" }

                log.debug("[{}] Interactive step '{}' answered: {}", plan.id, node.stepId, answer.take(80))

                runBlocking {
                    EventBus.post(
                        PlanStepEvent.Completed(
                            planId = plan.id,
                            stepName = node.stepId,
                            executionId = executionId,
                            output = answer,
                            durationMs = 0L,
                        ),
                    )
                }
            }

            is WorkflowNode.Sequence -> node.nodes.forEach {
                collectInteractiveInputs(it, steps, plan, executionId, scope)
            }

            // For parallel/conditional we still resolve ask nodes (sequentially here —
            // true parallel ask UX is complex and left for a future iteration).
            is WorkflowNode.Parallel -> node.nodes.forEach {
                collectInteractiveInputs(it, steps, plan, executionId, scope)
            }

            is WorkflowNode.Conditional -> {
                // Evaluate condition against the current scope map.
                // We do a simple string-based check here since AgenticScope is not yet built.
                val conditionMet = evaluateConditionOnMap(node.condition, scope)
                if (conditionMet) {
                    collectInteractiveInputs(node.node, steps, plan, executionId, scope)
                }
            }

            is WorkflowNode.Step -> Unit // AI step — nothing to do here
        }
    }

    /**
     * Simplified condition evaluator that works against a plain [Map] instead of [AgenticScope].
     * Used during the pre-collection pass where the agentic scope hasn't been built yet.
     * Mirrors the logic in [PlanConditionEvaluator].
     */
    private fun evaluateConditionOnMap(expression: String, scope: Map<String, Any>): Boolean {
        val equalsRe = Regex("""^\s*(\w+)\s*==\s*(.+?)\s*$""")
        val notEqualRe = Regex("""^\s*(\w+)\s*!=\s*(.+?)\s*$""")
        val containsRe = Regex("""^\s*(\w+)\s+contains\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)

        equalsRe.matchEntire(expression)?.let { m ->
            val (key, expected) = m.destructured
            val actual = scope[key]?.toString() ?: ""
            return actual.equals(expected.trim('"', '\''), ignoreCase = true)
        }
        notEqualRe.matchEntire(expression)?.let { m ->
            val (key, expected) = m.destructured
            val actual = scope[key]?.toString() ?: ""
            return !actual.equals(expected.trim('"', '\''), ignoreCase = true)
        }
        containsRe.matchEntire(expression)?.let { m ->
            val (key, substring) = m.destructured
            val actual = scope[key]?.toString() ?: ""
            return actual.contains(substring.trim('"', '\''), ignoreCase = true)
        }
        val bare = expression.trim()
        if (bare.matches(Regex("""\w+"""))) {
            val value = scope[bare]?.toString() ?: ""
            return value.isNotBlank() && value != "false"
        }
        return false
    }

    /**
     * Resolves `type: file` and `type: folder` inputs by reading the path value from [scope]
     * and replacing it with the actual file/folder content via [PlanFileContentReader].
     * Non-file inputs are left untouched.
     */
    private fun resolveFileInputs(plan: PlanDef, scope: MutableMap<String, Any>) {
        plan.inputs
            .filter { it.type == "file" || it.type == "folder" || it.type == "url" }
            .forEach { input ->
                val value = scope[input.key]?.toString()
                if (value.isNullOrBlank()) return@forEach
                log.debug("[{}] Resolving {} input '{}' from: {}", plan.id, input.type, input.key, value.take(100))
                val content = PlanFileContentReader.read(value, input)
                scope[input.key] = content
                log.debug("[{}] Injected {} chars for input '{}'", plan.id, content.length, input.key)
            }
    }

    private fun validateInputs(plan: PlanDef, inputs: Map<String, String>) {
        val missing = plan.inputs
            .filter { it.required && inputs[it.key].isNullOrBlank() }
            .map { it.key }

        require(missing.isEmpty()) {
            "Plan '${plan.id}' is missing required inputs: ${missing.joinToString()}"
        }
    }

    private fun logExecutionSummary(plan: PlanDef, output: String) {
        if (output.isBlank()) {
            log.warn(
                "[{}] Plan completed but result is blank. " +
                    "Verify that the last workflow step returned a non-empty response.",
                plan.id,
            )
        } else {
            log.debug("[{}] Plan completed. Output length: {} chars", plan.id, output.length)
        }
    }

    /** Returns true if any node in the workflow tree is an instance of [T]. */
    private inline fun <reified T : WorkflowNode> containsNode(node: WorkflowNode): Boolean = containsNodeClass(node, T::class.java)

    private fun containsNodeClass(node: WorkflowNode, type: Class<*>): Boolean = when {
        type.isInstance(node) -> true
        node is WorkflowNode.Sequence -> node.nodes.any { containsNodeClass(it, type) }
        node is WorkflowNode.Parallel -> node.nodes.any { containsNodeClass(it, type) }
        node is WorkflowNode.Conditional -> containsNodeClass(node.node, type)
        else -> false
    }

    /**
     * Returns the step id of the last leaf [WorkflowNode.Step] in execution order.
     * For a Sequence this is the last node's leaf; for a Parallel/Conditional it's the
     * last child's leaf. This is the step whose output is considered the plan's final result.
     */
    private fun lastLeafStepId(node: WorkflowNode): String? = when (node) {
        is WorkflowNode.Step -> node.stepId
        is WorkflowNode.Ask -> node.stepId
        is WorkflowNode.Sequence -> lastLeafStepId(node.nodes.last())
        is WorkflowNode.Parallel -> node.nodes.mapNotNull { lastLeafStepId(it) }.lastOrNull()
        is WorkflowNode.Conditional -> lastLeafStepId(node.node)
    }

    /**
     * Recursively maps a [WorkflowNode] to a built [UntypedAgent].
     * [listener] is threaded through and attached to every leaf step agent.
     * Since [PlanExecutionListener.inheritedBySubagents] = true, attaching it on the
     * root is sufficient — but we attach it to every leaf for explicit clarity.
     */
    private fun buildAgent(
        node: WorkflowNode,
        steps: Map<String, PlanStep>,
        plan: PlanDef,
        listener: PlanExecutionListener? = null,
    ): UntypedAgent? = when (node) {
        is WorkflowNode.Step -> buildStepAgent(node, steps, plan, listener)

        // Ask nodes are fully resolved before the agentic run — no agent needed.
        is WorkflowNode.Ask -> null

        is WorkflowNode.Sequence -> buildSequenceAgent(node, steps, plan, listener)

        is WorkflowNode.Parallel -> buildParallelAgent(node, steps, plan, listener)

        is WorkflowNode.Conditional -> buildConditionalAgent(node, steps, plan, listener)
    }

    /**
     * The step's `message` is the user prompt template; `{{variable}}` placeholders
     * are resolved by LangChain4j from the shared `AgenticScope`.
     * The step result is stored in scope under the step's own `id` as `outputKey`.
     *
     * Effective tools = [PlanStep.tools] if non-empty, else [PlanDef.tools].
     * An empty effective list means no tool provider is attached.
     */
    private fun buildStepAgent(
        node: WorkflowNode.Step,
        steps: Map<String, PlanStep>,
        plan: PlanDef,
        listener: PlanExecutionListener? = null,
    ): UntypedAgent {
        val step = steps[node.stepId]
            ?: error("Plan references unknown step '${node.stepId}'")

        val builder = AgenticServices.agentBuilder()
            .chatModel(chatModel)
            .name(step.id)
            .outputKey(step.id)
            .userMessage(step.message)

        step.system?.let { builder.systemMessage(it) }

        listener?.let { builder.listener(it) }

        // Resolve effective tools: step-level overrides plan-level default.
        val effectiveTools = step.tools.ifEmpty { plan.tools }
        if (effectiveTools.isNotEmpty()) {
            log.debug("[{}] step '{}' tools: {}", plan.id, step.id, effectiveTools)
            builder.toolProvider(PlanToolProvider(effectiveTools))
        }

        return builder.build()
    }

    /**
     * Maps [WorkflowNode.Sequence] → `AgenticServices.sequenceBuilder()`.
     * Sub-agents execute one-by-one; each result is available in scope for the next.
     */
    private fun buildSequenceAgent(
        node: WorkflowNode.Sequence,
        steps: Map<String, PlanStep>,
        plan: PlanDef,
        listener: PlanExecutionListener? = null,
    ): UntypedAgent? {
        val subAgents = node.nodes.mapNotNull { buildAgent(it, steps, plan, listener) }
        if (subAgents.isEmpty()) return null
        return AgenticServices.sequenceBuilder()
            .subAgents(*subAgents.toTypedArray())
            .build()
    }

    /**
     * Maps [WorkflowNode.Parallel] → `AgenticServices.parallelBuilder()`.
     * Sub-agents run concurrently; results are all written to scope before any dependent step.
     */
    private fun buildParallelAgent(
        node: WorkflowNode.Parallel,
        steps: Map<String, PlanStep>,
        plan: PlanDef,
        listener: PlanExecutionListener? = null,
    ): UntypedAgent? {
        val subAgents = node.nodes.mapNotNull { buildAgent(it, steps, plan, listener) }
        if (subAgents.isEmpty()) return null
        return AgenticServices.parallelBuilder()
            .subAgents(*subAgents.toTypedArray())
            .outputKey(node.outputKey)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build()
    }

    /**
     * Maps [WorkflowNode.Conditional] → `AgenticServices.conditionalBuilder()`.
     * The child agent only runs when the condition expression evaluates to `true` against the scope.
     */
    private fun buildConditionalAgent(
        node: WorkflowNode.Conditional,
        steps: Map<String, PlanStep>,
        plan: PlanDef,
        listener: PlanExecutionListener? = null,
    ): UntypedAgent? {
        val childAgent = buildAgent(node.node, steps, plan, listener) ?: return null
        val condition = node.condition

        return AgenticServices.conditionalBuilder()
            .subAgents(
                condition,
                { scope -> PlanConditionEvaluator.evaluate(condition, scope) },
                childAgent,
            )
            .build()
    }
}
