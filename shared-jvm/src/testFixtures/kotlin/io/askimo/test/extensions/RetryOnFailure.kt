/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.test.extensions

import io.askimo.core.logging.logger
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler

/**
 * Retries a test up to [maxAttempts] times if it fails.
 * The test only repeats on failure; a passing run stops immediately.
 *
 * Usage:
 * ```kotlin
 * @Test
 * @RetryOnFailure(maxAttempts = 3)
 * fun flakyTest() { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(RetryOnFailureExtension::class)
annotation class RetryOnFailure(val maxAttempts: Int = 3)

internal class RetryOnFailureExtension : TestExecutionExceptionHandler {

    private val log = logger<RetryOnFailureExtension>()

    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
        val annotation = context.requiredTestMethod.getAnnotation(RetryOnFailure::class.java)
            ?: context.requiredTestClass.getAnnotation(RetryOnFailure::class.java)
            ?: throw throwable

        val maxAttempts = annotation.maxAttempts
        if (maxAttempts <= 1) throw throwable

        val store = context.getStore(ExtensionContext.Namespace.create(RetryOnFailureExtension::class.java, context.uniqueId))
        val attempt = store.getOrDefault("attempt", Int::class.java, 1)

        log.warn("⚠️ Test failed on attempt $attempt of $maxAttempts: ${throwable.message}")

        if (attempt >= maxAttempts) {
            log.error("❌ All $maxAttempts attempts exhausted.")
            throw throwable
        }

        store.put("attempt", attempt + 1)

        // Re-invoke the test method directly for the next attempt
        val testInstance = context.requiredTestInstance
        val testMethod = context.requiredTestMethod
        try {
            log.info("🔄 Retrying... attempt ${attempt + 1} of $maxAttempts")
            testMethod.invoke(testInstance)
        } catch (retryException: java.lang.reflect.InvocationTargetException) {
            handleTestExecutionException(context, retryException.cause ?: retryException)
        }
    }
}
