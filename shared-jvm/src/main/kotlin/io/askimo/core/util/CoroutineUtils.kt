/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import kotlinx.coroutines.CancellationException

/**
 * Like [kotlin.runCatching], but rethrows [CancellationException] instead of wrapping it in a
 * [Result.failure]. The stdlib version catches every [Throwable], which silently swallows
 * coroutine cancellation (e.g. a `rememberCoroutineScope()` tied to a composable that left
 * composition) and turns it into a misleading "failure" instead of letting it propagate.
 *
 * Use this instead of [kotlin.runCatching] whenever [block] may call `suspend` functions.
 */
suspend inline fun <T> suspendRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
