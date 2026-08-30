/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory

inline fun <reified T> logger() = LoggerFactory.getLogger(T::class.java)

/**
 * Returns a [Logger] named after the *caller's* class/file, resolved via the current
 * thread's stack trace at call time. Works correctly for both top-level file properties
 * (e.g. `private val log = currentFileLogger()` at file scope, named e.g. `FooKt`) and
 * properties declared inside a class (named after the actual class).
 */
fun currentFileLogger(): Logger {
    val stackTrace = Thread.currentThread().stackTrace
    val callerClassName = stackTrace.getOrNull(2)?.className
        ?: Logger::class.java.name.also {
            LoggerFactory.getLogger(Logger::class.java)
                .warn(
                    "currentFileLogger(): could not resolve caller from stack trace (size={}), falling back to {}",
                    stackTrace.size,
                    it,
                )
        }
    return LoggerFactory.getLogger(callerClassName)
}

fun Logger.display(message: String) {
    println(message)
    this.info(message)
}

fun Logger.displayError(message: String, throwable: Throwable? = null) {
    println(message)
    throwable?.let {
        throwable.printStackTrace(System.err)
    }
    this.error(message, throwable)
}
