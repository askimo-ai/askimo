/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.domain

import kotlin.time.Clock
import kotlin.time.Instant

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val projectId: String? = null, // null = no project (general chat)
    val directiveId: String? = null,
    val isStarred: Boolean = false,
    /** True when the user has manually renamed this session — suppresses auto title-refresh. */
    val isUserRenamed: Boolean = false,
    /** Persistent user-selected resource collections for this session (chip state).
     * On-demand mentions (@mention) are tracked per-message, not here. */
    val activeResourceCollectionIds: List<String> = emptyList(),
)

const val SESSION_TITLE_MAX_LENGTH = 256

