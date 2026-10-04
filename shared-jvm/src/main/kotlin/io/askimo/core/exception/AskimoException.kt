/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.exception

/**
 * How a classified error should be handled by automatic retry loops.
 *
 * Cross-cutting across the user/system error split — e.g. [RemoteServerException] and
 * [RateLimitException] are user-facing errors that are also retryable, while
 * [AuthenticationException] is a user-facing error that is not.
 */
enum class RetryPolicy {
    /** User must fix something (auth, config, model choice) before retrying makes sense. */
    NONE,

    /**
     * Retry immediately, no backoff — but only after the caller applies a corrective
     * side effect first (e.g. shrink the context budget, disable sampling params,
     * drop a malformed tool call). The bounded attempt count is owned by the caller
     * (e.g. the context-retry loop in `sendStreamingMessageWithCallback`), not by this enum.
     */
    IMMEDIATE,

    /** Transient/overloaded backend — retry after a backoff delay (rate limits, 5xx, network blips). */
    BACKOFF,
}

/**
 * Base exception for all Askimo-specific errors.
 * Provides message keys for localization and distinguishes between user and system errors.
 */
sealed class AskimoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /**
     * Get the localization message key for this exception.
     */
    abstract fun getMessageKey(): String

    /**
     * Get the arguments to be used with the localization message template.
     */
    abstract fun getMessageArgs(): Map<String, String>

    /**
     * Whether this is a user error (can be fixed by user) or system error (needs support).
     */
    abstract fun isUserError(): Boolean

    /**
     * How automatic retry loops should treat this error. Defaults to [RetryPolicy.NONE] —
     * most errors require the user (or a config/cache change) to fix something before a
     * retry has any chance of succeeding. Override in specific subclasses that are known
     * to be transient (see [RetryPolicy] docs).
     */
    open val retryPolicy: RetryPolicy = RetryPolicy.NONE
}
