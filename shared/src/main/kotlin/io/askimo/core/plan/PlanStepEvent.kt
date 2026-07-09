/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import io.askimo.core.event.Event
import io.askimo.core.event.EventSource
import io.askimo.core.event.EventType
import java.time.Instant

/**
 * Events emitted by [PlanExecutionListener] for each step in a running plan.
 *
 * All variants are [EventType.INTERNAL] so they flow through [io.askimo.core.event.EventBus.internalEvents].
 *
 * @param planId      The id of the [io.askimo.core.plan.domain.PlanDef] being executed.
 * @param stepName    The name / outputKey of the step agent (matches the YAML step id).
 * @param executionId The [io.askimo.core.plan.domain.PlanExecution] record id, for correlation.
 */
sealed class PlanStepEvent : Event {
    abstract val planId: String
    abstract val stepName: String
    abstract val executionId: String

    override val source: EventSource get() = EventSource.SYSTEM

    /**
     * Fired immediately before an agent step is invoked.
     *
     * @param inputs Subset of the scope state passed as inputs to this step.
     */
    data class Started(
        override val planId: String,
        override val stepName: String,
        override val executionId: String,
        val inputs: Map<String, Any> = emptyMap(),
        override val timestamp: Instant = Instant.now(),
    ) : PlanStepEvent() {
        override val type = EventType.INTERNAL
        override fun getDetails() = "[$planId] Step '$stepName' started"
    }

    /**
     * Fired after a step agent returns successfully.
     *
     * @param output The raw output object returned by the agent (usually a String).
     * @param inputTokens Prompt/input token count for this step (nullable when unavailable).
     * @param outputTokens Completion/output token count for this step (nullable when unavailable).
     * @param totalTokens Total token count for this step (nullable when unavailable).
     * @param durationMs Wall-clock time for this step in milliseconds.
     */
    data class Completed(
        override val planId: String,
        override val stepName: String,
        override val executionId: String,
        val output: Any?,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
        val durationMs: Long = 0L,
        override val timestamp: Instant = Instant.now(),
    ) : PlanStepEvent() {
        override val type = EventType.INTERNAL
        override fun getDetails() = "[$planId] Step '$stepName' completed in ${durationMs}ms"
    }

    /**
     * Fired when execution reaches an interactive [WorkflowNode.Ask] step and pauses
     * to wait for the user's answer.
     *
     * The executor blocks on [channel] until [PlanInputChannel.answer] is called with
     * the user's response. The answer is then injected into the AgenticScope under
     * [inputKey] as the output key so subsequent steps can reference it via `{{inputKey}}`.
     *
     * @param question  The question text declared in the YAML `ask:` field.
     * @param inputKey  The scope key under which the answer will be stored (= step id).
     * @param channel   The blocking channel — call [PlanInputChannel.answer] to resume execution.
     */
    data class WaitingForInput(
        override val planId: String,
        override val stepName: String,
        override val executionId: String,
        val question: String,
        val inputKey: String,
        val channel: PlanInputChannel,
        override val timestamp: Instant = Instant.now(),
    ) : PlanStepEvent() {
        override val type = EventType.INTERNAL
        override fun getDetails() = "[$planId] Step '$stepName' waiting for user input"
    }

    /**
     * Fired when an agent step throws an exception.
     *
     * @param error The exception that caused the step to fail.
     * @param durationMs Wall-clock time until failure in milliseconds.
     */
    data class Failed(
        override val planId: String,
        override val stepName: String,
        override val executionId: String,
        val error: Throwable,
        val durationMs: Long = 0L,
        override val timestamp: Instant = Instant.now(),
    ) : PlanStepEvent() {
        override val type = EventType.INTERNAL
        override fun getDetails() = "[$planId] Step '$stepName' failed after ${durationMs}ms: ${error.message}"
    }
}
