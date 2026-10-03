/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.logging

import co.touchlab.kermit.Logger as KermitLogger

/**
 * Askimo custom Logger wrapper around Kermit.
 *
 * This provides a stable, project-specific logging abstraction that wraps Kermit's API.
 * In the future, if we replace Kermit with another logging library, only this file needs
 * to change — all calling code remains unchanged.
 *
 * Kermit logs output to stdout via Kermit's default configuration.
 */
class Logger(private val tag: String) {
    private val kermitLogger = KermitLogger.withTag(tag)

    fun trace(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            kermitLogger.v(throwable) { message }
        } else {
            kermitLogger.v { message }
        }
    }

    fun debug(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            kermitLogger.d(throwable) { message }
        } else {
            kermitLogger.d { message }
        }
    }

    fun info(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            kermitLogger.i(throwable) { message }
        } else {
            kermitLogger.i { message }
        }
    }

    fun warn(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            kermitLogger.w(throwable) { message }
        } else {
            kermitLogger.w { message }
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            kermitLogger.e(throwable) { message }
        } else {
            kermitLogger.e { message }
        }
    }

    // ── Overloads for backward compatibility with SLF4J varargs logging ──
    // Old SLF4J style: log.warn("msg with {} placeholder", arg1, arg2)
    // We ignore varargs and just log the message as-is for now.

    fun trace(message: String, vararg args: Any?) {
        kermitLogger.v { formatMessage(message, args) }
    }

    fun debug(message: String, vararg args: Any?) {
        kermitLogger.d { formatMessage(message, args) }
    }

    fun info(message: String, vararg args: Any?) {
        kermitLogger.i { formatMessage(message, args) }
    }

    fun warn(message: String, vararg args: Any?) {
        kermitLogger.w { formatMessage(message, args) }
    }

    fun error(message: String, vararg args: Any?) {
        kermitLogger.e { formatMessage(message, args) }
    }

    private fun formatMessage(message: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return message
        // Simple format string replacement for {} placeholders
        var result = message
        for (arg in args) {
            result = result.replaceFirst("{}", arg?.toString() ?: "null")
        }
        return result
    }

    /**
     * Display a message both to stdout and via the logger.
     */
    fun display(message: String) {
        println(message)
        info(message)
    }

    /**
     * Display an error message both to stderr and via the logger.
     */
    fun displayError(message: String, throwable: Throwable? = null) {
        println(message)
        throwable?.let {
            throwable.printStackTrace(System.err)
        }
        error(message, throwable)
    }
}

/**
 * Creates a [Logger] instance with a tag based on the reified type.
 * Usage: val log = logger<MyClass>()
 */
inline fun <reified T> logger(): Logger = Logger(T::class.simpleName ?: "Unknown")

/**
 * Returns a [Logger] instance named after the *caller's* class/file, resolved via the current
 * thread's stack trace at call time. Works correctly for both top-level file properties
 * (e.g. `private val log = currentFileLogger()` at file scope) and properties declared inside a class.
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

/**
 * Display a message both to stdout and via the logger.
 */
fun display(message: String) {
    println(message)
    Logger("display").info(message)
}

/**
 * Display an error message both to stderr and via the logger.
 */
fun displayError(message: String, throwable: Throwable? = null) {
    println(message)
    throwable?.let {
        throwable.printStackTrace(System.err)
    }
    Logger("displayError").error(message, throwable)
}
