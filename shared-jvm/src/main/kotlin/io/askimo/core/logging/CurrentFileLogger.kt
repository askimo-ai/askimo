/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.logging

/**
 * Returns a [Logger] instance named after the *caller's* class/file, resolved via the current
 * thread's stack trace at call time. Works correctly for both top-level file properties
 * (e.g. `private val log = currentFileLogger()` at file scope) and properties declared inside a class.
 *
 * JVM-only (relies on [Thread.getStackTrace]) — lives in `shared-jvm` rather than the
 * multiplatform `shared` module alongside [Logger]/[logger].
 */
fun currentFileLogger(): Logger {
    val stackTrace = Thread.currentThread().stackTrace
    val callerClassName = stackTrace.getOrNull(2)?.className
        ?: "Unknown".also {
            Logger("Logger").warn(
                "currentFileLogger(): could not resolve caller from stack trace (size=${stackTrace.size})",
            )
        }
    val simpleName = callerClassName.substringAfterLast('.')
    return Logger(simpleName)
}
