/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.user.domain

import kotlin.time.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * User profile data class representing the user's personal information
 * used for AI personalization.
 */
data class UserProfile(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val preferredTitle: String? = null, // Mr., Ms., Dr., etc.
    val occupation: String? = null,
    val location: String? = null,
    val timezone: String? = null,
    val bio: String? = null,
    val interests: List<String> = emptyList(),
    val preferences: Map<String, String> = emptyMap(),
    val createdAt: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    val updatedAt: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
)

