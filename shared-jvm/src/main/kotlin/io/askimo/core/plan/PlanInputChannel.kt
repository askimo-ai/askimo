/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * A one-shot synchronisation point between the [PlanExecutor] background thread and the UI.
 *
 * When [PlanExecutor] reaches a [io.askimo.core.plan.domain.WorkflowNode.Ask] node it:
 * 1. Creates a fresh [PlanInputChannel].
 * 2. Posts a [PlanStepEvent.WaitingForInput] event carrying this channel reference.
 * 3. Calls [waitForAnswer] — this **blocks the executor thread** until the UI responds.
 *
 * The UI (via [io.askimo.ui.plan.PlansViewModel]) receives the event, renders an inline
 * question panel, and calls [answer] when the user submits their response.
 *
 * Each channel is single-use: it is created per `ask` step and discarded after answer delivery.
 */
class PlanInputChannel {

    private val queue = SynchronousQueue<String>()

    /** `true` once [answer] has been called (informational only). */
    @Volatile
    var answered: Boolean = false
        private set

    /**
     * Blocks the calling thread until [answer] supplies a response or [timeoutMs] elapses.
     *
     * @param timeoutMs Maximum wait time in milliseconds. Default = 10 minutes.
     * @return The user's answer string, or an empty string on timeout.
     */
    fun waitForAnswer(timeoutMs: Long = 10 * 60 * 1_000L): String = queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: ""

    /**
     * Delivers the user's [response] to the waiting executor thread.
     *
     * Should be called from the UI layer (ViewModel) in response to user input.
     * Safe to call from any thread.
     */
    fun answer(response: String) {
        answered = true
        queue.offer(response, 5, TimeUnit.SECONDS)
    }

    /**
     * Unblocks the waiting executor with an empty string — used when the plan is
     * cancelled or the user dismisses the question without answering.
     */
    fun cancel() {
        queue.offer("", 1, TimeUnit.SECONDS)
    }
}
