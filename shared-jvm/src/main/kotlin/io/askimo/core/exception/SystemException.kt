/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.exception

/**
 * System/internal error that requires support contact.
 * Used for unexpected errors that cannot be classified or fixed by the user.
 */
class SystemException(
    message: String,
    cause: Throwable? = null,
) : AskimoException(message, cause) {

    override fun isUserError() = false

    override fun getMessageKey() = "error.system"

    override fun getMessageArgs() = mapOf(
        "errorCode" to (message ?: "Unknown error"),
    )
}
