/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Flags `launch { ... }` called directly inside a streaming/event callback parameter — e.g.
 * `onToken`, `onThinkingToken`, `onToolStarted`, `onToolFinished`, `onToolCall`, `onStatus`.
 *
 * ### The bug this prevents
 * Each invocation of a streaming callback spawns its **own independently-scheduled
 * coroutine**. A `Mutex` around the body makes any single write atomic, but guarantees
 * nothing about the *order* in which those independently-launched coroutines reach the
 * lock — on a multi-threaded dispatcher, a later-launched coroutine can win the race and
 * apply before an earlier one. The text/events are never lost, just silently applied out
 * of order — a real, previously shipped bug in this codebase (see
 * `io.askimo.ui.common.OrderedEventQueue`'s KDoc).
 *
 * **Wrong:**
 * ```kotlin
 * sendStreamingMessageWithCallback(
 *     onToken = { token ->
 *         scope.launch { thread.appendChunk(token) }   // races with other onToken calls
 *     },
 * )
 * ```
 *
 * **Correct:** route the mutation through a single-consumer ordered queue instead of
 * launching a new coroutine per event.
 * ```kotlin
 * sendStreamingMessageWithCallback(
 *     onToken = { token ->
 *         thread.enqueue { thread.appendChunk(token) }   // OrderedEventQueue.enqueue
 *     },
 * )
 * ```
 *
 * This rule is a heuristic, syntax-only check (no type resolution): it matches on the
 * callback parameter's *name*, not its declared type, so it only fires for the known
 * streaming/event callback naming convention used across the provider and agent APIs in
 * this codebase. It will not catch every possible variant of this bug (e.g. `launch`
 * reached indirectly through a helper function), and it may rarely flag a callback that
 * happens to share one of these names but isn't actually part of an ordered event stream
 * — in that case, suppress with `@Suppress("UnsafeLaunchInStreamingCallback")` and leave a
 * short comment explaining why ordering doesn't matter there.
 */
class UnsafeLaunchInStreamingCallback(config: Config = Config.empty) :
    Rule(
        config,
        "`launch { }` found directly inside a streaming/event callback parameter (e.g. " +
            "onToken, onToolStarted). Each invocation spawns an independently-scheduled " +
            "coroutine, so a Mutex alone cannot guarantee callbacks apply in arrival order — " +
            "fast streams can and do reorder text/events. Route the mutation through a " +
            "single-consumer ordered queue (e.g. OrderedEventQueue.enqueue) instead of " +
            "launching directly in the callback.",
    ) {

    companion object {
        private val CALLBACK_NAME_PATTERN = Regex("^on(Token|Thinking|Tool|Status)")
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (expression.calleeExpression?.text != "launch") return

        val enclosingLambda = expression.getParentOfType<KtLambdaExpression>(strict = true) ?: return
        val valueArgument = enclosingLambda.getParentOfType<KtValueArgument>(strict = false) ?: return
        val argumentName = valueArgument.getArgumentName()?.asName?.asString() ?: return

        if (CALLBACK_NAME_PATTERN.containsMatchIn(argumentName)) {
            report(
                Finding(
                    entity = Entity.from(expression),
                    message = "`launch { }` inside streaming callback `$argumentName`. Each " +
                        "callback invocation races independently — route through an ordered " +
                        "single-consumer queue (e.g. OrderedEventQueue.enqueue) instead.",
                ),
            )
        }
    }
}
