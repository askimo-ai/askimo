/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.state

/**
 * Enumeration of indexing status states
 */
enum class IndexStatus {
    NOT_STARTED,
    QUEUED,
    INDEXING,
    READY,
    WATCHING,
    FAILED,
}

