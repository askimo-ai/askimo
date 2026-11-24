/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serializes a stream of actions so they apply in **exactly the order they were enqueued**,
 * no matter how many threads a multi-threaded dispatcher schedules them on.
 *
 * ### The bug this prevents
 * A naive streaming callback like `onToken = { token -> scope.launch { thread.appendChunk(token) } }`
 * (append guarded by a `Mutex`) spawns one independently-scheduled coroutine per token. The
 * `Mutex` makes each write atomic but guarantees nothing about lock-acquisition *order* — on a
 * multi-threaded dispatcher, a later-launched coroutine can win the race under fast streaming.
 * Text is never lost, just silently applied out of order. **Never reintroduce a per-event
 * `scope.launch` for ordered mutations** — route them through an [OrderedEventQueue] instead.
 *
 * ### How ordering is guaranteed
 * [enqueue] is a synchronous, non-suspending send — call it directly from the callback thread
 * (which already invokes callbacks one at a time, in arrival order) so the send never races.
 * Exactly one consumer coroutine (from [startProcessing]) drains the queue strictly FIFO.
 *
 * Not reusable after [close] — create a new instance per stream/turn.
 */
class OrderedEventQueue {
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /**
     * Enqueues [action] to run on the single consumer coroutine, preserving call order.
     * Must be invoked synchronously from the thread that observed the event — never from
     * inside another `launch`, which would reintroduce the very race this class exists to
     * avoid. Safe to call after [close] (the action is silently dropped) so late/superseded
     * callbacks can't throw.
     */
    fun enqueue(action: suspend () -> Unit) {
        channel.trySend(action)
    }

    /**
     * Starts the single coroutine that drains [channel] in FIFO order, applying each action in
     * turn. Returns the [Job] so callers can await full drain (after [close]) before reading
     * any state the actions mutated.
     */
    fun startProcessing(scope: CoroutineScope): Job = scope.launch {
        for (action in channel) {
            action()
        }
    }

    /**
     * Signals that no more actions will be enqueued, letting [startProcessing]'s loop finish
     * once every already-enqueued action has run. Callers must join the [Job] returned by
     * [startProcessing] after calling this to be sure every action has actually applied —
     * closing alone does not wait for the drain.
     */
    fun close() {
        channel.close()
    }
}
