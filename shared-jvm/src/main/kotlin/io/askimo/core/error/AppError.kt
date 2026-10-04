/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.error

/**
 * Common application-level error vocabulary shared across all service classes.
 *
 * | Variant          | When to use                                         |
 * |------------------|-----------------------------------------------------|
 * | [NotFound]       | Entity looked up by id did not exist                |
 * | [DuplicateEntry] | Uniqueness constraint violated (e.g. display name)  |
 * | [Unexpected]     | Any other unrecoverable or unanticipated failure    |
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * A requested entity could not be found.
     * @param id         The identifier that was looked up.
     * @param entityType Optional human-readable type label, e.g. "Provider instance".
     */
    class NotFound(
        val id: String,
        val entityType: String = "",
    ) : AppError(
        if (entityType.isNotBlank()) "$entityType '$id' not found" else "'$id' not found",
    )

    /**
     * A uniqueness constraint was violated.
     * @param field The constrained field name, e.g. "display name".
     * @param value The conflicting value.
     */
    class DuplicateEntry(
        val field: String,
        val value: String,
    ) : AppError("A $field with value \"$value\" already exists")

    /**
     * An unexpected or unclassified failure.
     * @param detail Short description of the failure context.
     * @param cause  The underlying exception, if any.
     */
    class Unexpected(
        detail: String = "An unexpected error occurred",
        cause: Throwable? = null,
    ) : AppError(detail, cause)
}

/** Casts this [Throwable] to [AppError], or returns null if it is not one. */
fun Throwable.asAppError(): AppError? = this as? AppError
