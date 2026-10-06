/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

object TimeUtil {
    private val instantDisplayFmt = DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")

    /**
     * Parses a stored `Instant` column value, tolerating every format this codebase has ever
     * written to SQLite for an Instant column:
     * - Canonical ISO-8601 with `Z`/explicit offset (written by [io.askimo.core.db.SQLiteInstantColumnType]
     *   and all current SQLDelight repositories)
     * - ISO-8601 without an offset (assumed UTC)
     * - Legacy space-separated format with no `T`/offset (e.g. `2026-09-09 19:46:12[.SSS]`) —
     *   written by the old Exposed `datetime()` column type, or by a SQLite column `DEFAULT
     *   (datetime('now'))` kicking in — assumed UTC
     *
     * Returns [kotlin.time.Instant] so callers building `shared`-module domain entities
     * (e.g. [io.askimo.core.chat.domain.ChatMessage]) don't need their own conversion.
     */
    fun parseInstant(raw: String): Instant = runCatching {
        Instant.parse(raw)
    }.getOrElse {
        runCatching {
            val normalized = raw.trim().replace(' ', 'T')
            val withOffset = if (normalized.endsWith('Z') || normalized.contains('+')) normalized else "${normalized}Z"
            Instant.parse(withOffset)
        }.getOrElse {
            LocalDateTime.parse(raw.trim().replace(' ', 'T')).toInstant(TimeZone.UTC)
        }
    }

    /**
     * Formats an Instant with the standard display format for the given locale,
     * converted to the user's local timezone.
     * Default format: "MMM dd, yyyy HH:mm" (e.g., "Nov 15, 2025 14:30")
     *
     * @param instant The Instant to format
     * @param locale The locale to use for formatting (defaults to system locale)
     * @return The formatted date-time string in the user's local timezone
     */
    fun formatDisplay(instant: Instant, locale: Locale = Locale.getDefault()): String = format(instant, "MMM dd, yyyy HH:mm", locale)

    /**
     * Formats an Instant with a custom pattern for the given locale,
     * converted to the user's local timezone.
     *
     * @param instant The Instant to format
     * @param pattern The date-time pattern (e.g., "MMM dd, yyyy HH:mm")
     * @param locale The locale to use for formatting (defaults to system locale)
     * @return The formatted date-time string in the user's local timezone
     */
    fun format(instant: Instant, pattern: String, locale: Locale = Locale.getDefault()): String {
        val formatter = DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault())
        return formatter.format(instant.toJavaInstant())
    }

    /**
     * Formats an Instant to user's local time for display.
     * Format: "MMM dd, HH:mm:ss" (e.g., "Nov 30, 14:30:45")
     *
     * @param instant The Instant to format
     * @param locale The locale to use for formatting (defaults to system locale)
     * @return The formatted time string in user's local timezone
     */
    fun formatInstantDisplay(instant: Instant, locale: Locale = Locale.getDefault()): String {
        val formatter = instantDisplayFmt.withLocale(locale)
        return instant.toJavaInstant().atZone(ZoneId.systemDefault()).format(formatter)
    }

    /**
     * Formats a duration given in milliseconds into a human-readable string,
     * showing hours, minutes, and seconds (sub-second precision is dropped).
     *
     * Examples:
     * - 500      → "< 1s"
     * - 3_200    → "3s"
     * - 80_617   → "1m 20s"
     * - 3_723_000 → "1h 2m 3s"
     *
     * @param durationMs Total duration in milliseconds.
     * @param hourLabel   Localised label for hours   (e.g. "h").
     * @param minuteLabel Localised label for minutes (e.g. "m").
     * @param secondLabel Localised label for seconds (e.g. "s").
     * @param lessThanOne Localised string shown when duration is less than one second (e.g. "< 1s").
     */
    fun formatDurationMs(
        durationMs: Long,
        hourLabel: String = "h",
        minuteLabel: String = "m",
        secondLabel: String = "s",
        lessThanOne: String = "< 1s",
    ): String {
        val totalSeconds = durationMs / 1_000
        if (totalSeconds == 0L) return lessThanOne
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append("${hours}$hourLabel ")
            if (hours > 0 || minutes > 0) append("${minutes}$minuteLabel ")
            append("${seconds}$secondLabel")
        }.trim()
    }

    /**
     * Formats an [Instant] as a full locale-aware date and time string suitable for tooltips.
     * Example: "Saturday, June 21, 2026 at 3:45:00 PM"
     *
     * @param instant The Instant to format
     * @param locale  The locale to use for formatting (defaults to system locale)
     * @return The formatted date-time string in the user's local timezone
     */
    fun formatFullDateTime(instant: Instant, locale: Locale = Locale.getDefault()): String {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant.toJavaInstant())
    }
}
