/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent.domain

/**
 * Controls who can see and use a skill.
 *
 * - [PRIVATE]  — stored only on the local device, never synced.
 * - [TEAM]     — synced to the Askimo Team server; visible to all team members.
 * - [PUBLIC]   — reserved for a future public marketplace.
 */
enum class SkillVisibility {
    PRIVATE,
    TEAM,
    PUBLIC,
    ;

    companion object {
        /** Parse from a frontmatter string value, defaulting to [PRIVATE] on unknown input. */
        fun fromString(value: String?): SkillVisibility = entries
            .firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: PRIVATE
    }
}
