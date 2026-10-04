/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.user.repository

import io.askimo.core.db.AbstractRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.db.sqldelight.User_profiles
import io.askimo.core.user.domain.UserProfile
import io.askimo.core.util.TimeUtil
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.UUID
import kotlin.time.Clock

/** Current wall-clock time as a [kotlinx.datetime.LocalDateTime], matching [UserProfile]'s time fields. */
private fun nowLocalDateTime() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

/**
 * Maps a generated [User_profiles] row to the shared [UserProfile] domain object.
 * Interests/preferences are loaded separately and merged in by the caller (mirrors the
 * original Exposed repository's [getProfile] composition).
 *
 * `created_at`/`updated_at` are parsed via [TimeUtil.parseLocalDateTime], which tolerates both
 * the canonical ISO-8601 format written by this repository and the legacy space-separated
 * format written by the old Exposed `javatime.datetime()` column type (e.g.
 * `2026-09-09 12:46:12.696`) — no data migration needed, rows written under any historical
 * schema remain readable.
 */
private fun User_profiles.toUserProfile(): UserProfile = UserProfile(
    id = id,
    name = name,
    email = email,
    preferredTitle = preferred_title,
    occupation = occupation,
    location = location,
    timezone = timezone,
    bio = bio,
    createdAt = TimeUtil.parseLocalDateTime(created_at),
    updatedAt = TimeUtil.parseLocalDateTime(updated_at),
)

/**
 * Repository for managing user profiles.
 * Note: This system assumes a single user profile (single-user application).
 */
class UserProfileRepository internal constructor(
    databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) : AbstractRepository(databaseManager) {

    companion object {
        private const val DEFAULT_PROFILE_ID = "default"
    }

    /**
     * Get the user profile. Creates a default profile if none exists.
     * Synchronized to prevent concurrent first-login callers from racing to insert
     * the default row simultaneously.
     *
     * @return The user profile
     */
    @Synchronized
    fun getProfile(): UserProfile {
        // 1. Check if profile exists
        val profileRow = db.userProfilesQueries.selectById(DEFAULT_PROFILE_ID).executeAsOneOrNull()

        // 2. Create default profile if absent (one separate transaction, never nested)
        if (profileRow == null) {
            val defaultProfile = UserProfile(
                id = DEFAULT_PROFILE_ID,
                createdAt = nowLocalDateTime(),
                updatedAt = nowLocalDateTime(),
            )
            saveProfile(defaultProfile)
            return defaultProfile
        }

        // 3. Load the existing profile
        val profile = profileRow.toUserProfile()

        val interests = db.userInterestsQueries.selectByProfileId(profile.id).executeAsList()
            .map { it.interest }

        val preferences = db.userPreferencesQueries.selectByProfileId(profile.id).executeAsList()
            .associate { it.key to it.value_ }

        return profile.copy(interests = interests, preferences = preferences)
    }

    /**
     * Save or update the user profile.
     *
     * @param profile The profile to save
     * @return The saved profile
     */
    @Synchronized
    fun saveProfile(profile: UserProfile): UserProfile {
        val profileToSave = profile.copy(
            id = DEFAULT_PROFILE_ID,
            updatedAt = nowLocalDateTime(),
        )

        db.transaction {
            val exists = db.userProfilesQueries.selectById(DEFAULT_PROFILE_ID).executeAsOneOrNull() != null

            if (exists) {
                db.userProfilesQueries.updateProfile(
                    name = profileToSave.name,
                    email = profileToSave.email,
                    preferredTitle = profileToSave.preferredTitle,
                    occupation = profileToSave.occupation,
                    location = profileToSave.location,
                    timezone = profileToSave.timezone,
                    bio = profileToSave.bio,
                    updatedAt = profileToSave.updatedAt.toString(),
                    id = DEFAULT_PROFILE_ID,
                )
            } else {
                db.userProfilesQueries.insertProfile(
                    id = profileToSave.id,
                    name = profileToSave.name,
                    email = profileToSave.email,
                    preferredTitle = profileToSave.preferredTitle,
                    occupation = profileToSave.occupation,
                    location = profileToSave.location,
                    timezone = profileToSave.timezone,
                    bio = profileToSave.bio,
                    createdAt = profileToSave.createdAt.toString(),
                    updatedAt = profileToSave.updatedAt.toString(),
                )
            }

            saveInterests(profileToSave.id, profileToSave.interests)
            savePreferences(profileToSave.id, profileToSave.preferences)
        }

        return profileToSave
    }

    /**
     * Update specific fields of the profile without affecting others.
     *
     * @param updates Map of field names to new values
     * @return The updated profile
     */
    @Synchronized
    fun updateProfile(updates: Map<String, Any?>): UserProfile {
        val currentProfile = getProfile()

        val updatedProfile = currentProfile.copy(
            name = updates["name"] as? String ?: currentProfile.name,
            email = updates["email"] as? String ?: currentProfile.email,
            preferredTitle = updates["preferredTitle"] as? String ?: currentProfile.preferredTitle,
            occupation = updates["occupation"] as? String ?: currentProfile.occupation,
            location = updates["location"] as? String ?: currentProfile.location,
            timezone = updates["timezone"] as? String ?: currentProfile.timezone,
            bio = updates["bio"] as? String ?: currentProfile.bio,
            interests = (updates["interests"] as? List<*>)?.filterIsInstance<String>() ?: currentProfile.interests,
            preferences = (updates["preferences"] as? Map<*, *>)?.entries?.associate {
                it.key.toString() to it.value.toString()
            } ?: currentProfile.preferences,
        )

        return saveProfile(updatedProfile)
    }

    /**
     * Clear all profile data (reset to default).
     */
    fun clearProfile() {
        db.transaction {
            db.userInterestsQueries.deleteByProfileId(DEFAULT_PROFILE_ID)
            db.userPreferencesQueries.deleteByProfileId(DEFAULT_PROFILE_ID)
            db.userProfilesQueries.deleteById(DEFAULT_PROFILE_ID)
        }
    }

    /**
     * Get personalization context string for AI prompts.
     * Returns null if no meaningful personalization data exists.
     *
     * @return Formatted personalization context or null
     */
    fun getPersonalizationContext(): String? {
        val profile = getProfile()

        val contextParts = mutableListOf<String>()

        profile.name?.let { contextParts.add("User's name: $it") }
        profile.preferredTitle?.let { contextParts.add("Preferred title: $it") }
        profile.occupation?.let { contextParts.add("Occupation: $it") }
        profile.location?.let { contextParts.add("Location: $it") }

        if (profile.interests.isNotEmpty()) {
            contextParts.add("Interests: ${profile.interests.joinToString(", ")}")
        }

        profile.bio?.let { contextParts.add("About: $it") }

        return if (contextParts.isEmpty()) null else contextParts.joinToString(". ")
    }

    /**
     * Save interests for a profile.
     */
    private fun saveInterests(profileId: String, interests: List<String>) {
        db.userInterestsQueries.deleteByProfileId(profileId)
        val now = nowLocalDateTime().toString()
        interests.forEach { interest ->
            db.userInterestsQueries.insertInterest(
                id = UUID.randomUUID().toString(),
                profileId = profileId,
                interest = interest,
                createdAt = now,
            )
        }
    }

    /**
     * Save preferences for a profile.
     */
    private fun savePreferences(profileId: String, preferences: Map<String, String>) {
        db.userPreferencesQueries.deleteByProfileId(profileId)
        val now = nowLocalDateTime().toString()
        preferences.forEach { (key, value) ->
            db.userPreferencesQueries.insertPreference(
                id = UUID.randomUUID().toString(),
                profileId = profileId,
                key = key,
                value = value,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    /**
     * Get a specific preference value.
     *
     * @param key The preference key
     * @return The preference value or null if not found
     */
    fun getPreference(key: String): String? = db.userPreferencesQueries.selectValue(DEFAULT_PROFILE_ID, key).executeAsOneOrNull()

    /**
     * Set a specific preference value.
     *
     * @param key The preference key
     * @param value The preference value
     */
    fun setPreference(key: String, value: String) {
        db.transaction {
            val exists = db.userPreferencesQueries.selectValue(DEFAULT_PROFILE_ID, key).executeAsOneOrNull() != null
            val now = nowLocalDateTime().toString()

            if (exists) {
                db.userPreferencesQueries.updatePreference(
                    value = value,
                    updatedAt = now,
                    profileId = DEFAULT_PROFILE_ID,
                    key = key,
                )
            } else {
                db.userPreferencesQueries.insertPreference(
                    id = UUID.randomUUID().toString(),
                    profileId = DEFAULT_PROFILE_ID,
                    key = key,
                    value = value,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
    }
}
