/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.preferences

import io.askimo.core.util.AskimoHome
import java.nio.file.Files
import java.time.LocalDate
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral tests for the share-prompt state machine in [AccountPreferences] — the
 * positive gate, the 100-message threshold, snoozing, and permanent dismissal.
 * [PreferencesStorageTest] only checks value round-tripping through the property file;
 * these guard the actual gating logic against regressions.
 */
class AccountPreferencesSharePromptTest {

    /** Seeds a raw key into the device property file — used to simulate a snooze date that
     *  has (or hasn't) already elapsed, bypassing the private date setters. */
    private fun seedDeviceProperty(key: String, value: String) {
        val file = AskimoHome.base().resolve("prefs/accounts/__device__.properties")
        val properties = Properties()
        if (Files.isRegularFile(file)) {
            Files.newInputStream(file).use(properties::load)
        }
        properties.setProperty(key, value)
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { properties.store(it, null) }
    }

    private fun withDevicePrefs(block: (AccountPreferences) -> Unit) {
        val root = Files.createTempDirectory("askimo-share-prompt-prefs")
        AskimoHome.withTestBase(root).use { block(AccountPreferences.device()) }
    }

    // ── Positive gate ────────────────────────────────────────────────────────

    @Test
    fun `never shows the share prompt to a user who never starred positively`() = withDevicePrefs { prefs ->
        assertFalse(prefs.hasStarredPositively())
        assertFalse(prefs.shouldShowSharePrompt(sentMessageCount = 1_000))
    }

    @Test
    fun `markStarredPositively opens the positive gate`() = withDevicePrefs { prefs ->
        prefs.markStarredPositively()
        assertTrue(prefs.hasStarredPositively())
    }

    @Test
    fun `legacy PERMANENTLY_DONE star state is inferred as positive on first read`() = withDevicePrefs { prefs ->
        seedDeviceProperty("star.prompt_state", AccountPreferences.StarPromptState.PERMANENTLY_DONE.name)

        assertTrue(prefs.hasStarredPositively())
    }

    @Test
    fun `legacy NEVER_SHOWN star state is not inferred as positive`() = withDevicePrefs { prefs ->
        assertFalse(prefs.hasStarredPositively())
    }

    // ── Power-user message-count threshold (boundary at 100) ────────────────────

    @Test
    fun `does not show one message below the power-user threshold`() = withDevicePrefs { prefs ->
        prefs.markStarredPositively()
        assertFalse(prefs.shouldShowSharePrompt(sentMessageCount = 99))
    }

    @Test
    fun `shows exactly at the power-user threshold`() = withDevicePrefs { prefs ->
        prefs.markStarredPositively()
        assertTrue(prefs.shouldShowSharePrompt(sentMessageCount = 100))
    }

    // ── Snooze-date branch ───────────────────────────────────────────────────

    @Test
    fun `stays hidden immediately after snoozing even past the message threshold`() = withDevicePrefs { prefs ->
        prefs.markStarredPositively()
        prefs.snoozeSharePrompt()

        assertFalse(prefs.shouldShowSharePrompt(sentMessageCount = 1_000))
    }

    @Test
    fun `stays hidden before the snooze period has elapsed`() = withDevicePrefs { prefs ->
        prefs.markStarredPositively()
        prefs.snoozeSharePrompt()
        seedDeviceProperty("share.snoozed_at", LocalDate.now().minusDays(10).toString())

        assertFalse(prefs.shouldShowSharePrompt(sentMessageCount = 1_000))
    }

    @Test
    fun `re-shows once the snooze period has elapsed`() = withDevicePrefs { prefs ->
        prefs.markStarredPositively()
        prefs.snoozeSharePrompt()
        seedDeviceProperty("share.snoozed_at", LocalDate.now().minusDays(31).toString())

        assertTrue(prefs.shouldShowSharePrompt(sentMessageCount = 1_000))
    }

    // ── Permanent-dismiss branch ─────────────────────────────────────────────

    @Test
    fun `never shows again once permanently dismissed, regardless of message count`() = withDevicePrefs { prefs ->
        prefs.markStarredPositively()
        prefs.dismissSharePromptPermanently()

        assertFalse(prefs.shouldShowSharePrompt(sentMessageCount = Int.MAX_VALUE))
    }

    // ── Full lifecycle regression guard ──────────────────────────────────────

    @Test
    fun `full lifecycle - not yet eligible, eligible, snoozed, re-eligible, then permanently dismissed`() = withDevicePrefs { prefs ->
        prefs.markStarredPositively()

        // Below threshold: not eligible yet.
        assertFalse(prefs.shouldShowSharePrompt(sentMessageCount = 50))

        // Crosses threshold: eligible.
        assertTrue(prefs.shouldShowSharePrompt(sentMessageCount = 150))

        // User clicks "Maybe later" — hidden immediately, even though still above threshold.
        prefs.snoozeSharePrompt()
        assertFalse(prefs.shouldShowSharePrompt(sentMessageCount = 150))

        // Snooze period elapses — eligible again.
        seedDeviceProperty("share.snoozed_at", LocalDate.now().minusDays(31).toString())
        assertTrue(prefs.shouldShowSharePrompt(sentMessageCount = 150))

        // User shares — permanently hidden from now on, no matter the message count.
        prefs.dismissSharePromptPermanently()
        assertFalse(prefs.shouldShowSharePrompt(sentMessageCount = 150))
    }
}
