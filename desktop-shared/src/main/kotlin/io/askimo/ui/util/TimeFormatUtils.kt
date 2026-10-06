/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Formats milliseconds to a human-readable string with two consecutive time units.
 *
 * Examples:
 * - 500ms -> "500ms"
 * - 1500ms -> "1s 500ms"
 * - 65000ms -> "1m 5s"
 * - 3665000ms -> "1h 1m"
 * - 90061000ms -> "1d 1h"
 */
private data class DurationComponents(
    val days: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
    val millis: Long,
)

private fun decompose(ms: Long): DurationComponents = DurationComponents(
    days = ms / 86400000,
    hours = ms % 86400000 / 3600000,
    minutes = ms % 3600000 / 60000,
    seconds = ms % 60000 / 1000,
    millis = ms % 1000,
)

fun formatDuration(ms: Long): String {
    if (ms < 0) return "0ms"

    val (days, hours, minutes, seconds, millis) = decompose(ms)

    return when {
        days > 0 -> {
            if (hours > 0) {
                "${days}d ${hours}h"
            } else if (minutes > 0) {
                "${days}d ${minutes}m"
            } else if (seconds > 0) {
                "${days}d ${seconds}s"
            } else {
                "${days}d"
            }
        }

        hours > 0 -> {
            if (minutes > 0) {
                "${hours}h ${minutes}m"
            } else if (seconds > 0) {
                "${hours}h ${seconds}s"
            } else {
                "${hours}h"
            }
        }

        minutes > 0 -> {
            if (seconds > 0) {
                "${minutes}m ${seconds}s"
            } else if (millis > 0) {
                "${minutes}m ${millis}ms"
            } else {
                "${minutes}m"
            }
        }

        seconds > 0 -> {
            if (millis > 0) {
                "${seconds}s ${millis}ms"
            } else {
                "${seconds}s"
            }
        }

        else -> "${millis}ms"
    }
}

/**
 * Formats milliseconds to a detailed string with up to 4 consecutive time units for tooltips.
 *
 * Examples:
 * - 500ms -> "500 milliseconds"
 * - 1500ms -> "1 second 500 milliseconds"
 * - 65000ms -> "1 minute 5 seconds"
 * - 3665000ms -> "1 hour 1 minute 5 seconds"
 * - 90061000ms -> "1 day 1 hour 1 minute 1 second"
 */
fun formatDurationDetailed(ms: Long): String {
    if (ms < 0) return "0 milliseconds"

    val (days, hours, minutes, seconds, millis) = decompose(ms)

    val parts = mutableListOf<String>()

    if (days > 0) parts.add(pluralize(days, "day"))
    if (hours > 0) parts.add(pluralize(hours, "hour"))
    if (minutes > 0) parts.add(pluralize(minutes, "minute"))
    if (seconds > 0) parts.add(pluralize(seconds, "second"))
    if (millis > 0 && parts.size < 4) parts.add(pluralize(millis, "millisecond"))

    // Take up to 4 consecutive units
    val selected = parts.take(4)

    return if (selected.isEmpty()) {
        "0 milliseconds"
    } else {
        selected.joinToString(" ")
    }
}

private fun pluralize(value: Long, unit: String): String = if (value == 1L) "$value $unit" else "$value ${unit}s"

/**
 * Formats [instant] as `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC — pure Kotlin,
 */
fun formatUtcTimestamp(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.UTC)
    return buildString {
        append(dt.year.toString().padStart(4, '0'))
        append('-')
        append(dt.month.number.toString().padStart(2, '0'))
        append('-')
        append(dt.day.toString().padStart(2, '0'))
        append('T')
        append(dt.hour.toString().padStart(2, '0'))
        append(':')
        append(dt.minute.toString().padStart(2, '0'))
        append(':')
        append(dt.second.toString().padStart(2, '0'))
        append('Z')
    }
}
