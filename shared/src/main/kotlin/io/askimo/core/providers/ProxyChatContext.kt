/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Thread-local holder for the identifiers the proxy server needs to correlate and
 * persist chat messages during a proxied streaming call.
 *
 * **Lifecycle**
 * 1. The session manager pre-generates [Context.assistantMessageId] and calls [set] on
 *    the coroutine thread immediately before invoking the streaming send function.
 * 2. The correlating HTTP client builder calls [get] on every HTTP `send()` and merges
 *    [Context.toHeaderMap] into the outgoing request headers.
 * 3. The session manager calls [clear] in a `finally` block after the streaming call returns.
 *
 * The `ThreadLocal` approach is safe here because the streaming send is a blocking call
 * (backed by a [java.util.concurrent.CountDownLatch]) that does not suspend or switch
 * threads between setting the context and making the HTTP request.
 *
 * Only populated when a proxy-backed provider is active — for direct providers the
 * headers are absent and the context is never set.
 */
object ProxyChatContext {

    data class Context(
        val sessionId: String,
        val userMessageId: String,
        val assistantMessageId: String,
    ) {
        /**
         * Returns the HTTP headers the proxy server reads to correlate and persist
         * chat messages during the streaming call.
         */
        fun toHeaderMap(): Map<String, String> = mapOf(
            "X-Session-Id" to sessionId,
            "X-User-Message-Id" to userMessageId,
            "X-Assistant-Message-Id" to assistantMessageId,
        )
    }

    private val current: ThreadLocal<Context?> = ThreadLocal.withInitial { null }

    /** Set the proxy context for the current thread. Must be cleared in a `finally` block. */
    fun set(context: Context) = current.set(context)

    /** Returns the current thread's proxy context, or `null` if not in a proxy call. */
    fun get(): Context? = current.get()

    /** Clears the proxy context for the current thread. */
    fun clear() = current.remove()
}
