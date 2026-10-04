/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.datetime

import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import io.askimo.tools.ToolResponseBuilder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date and time tools that give the AI accurate current temporal context
 * without relying on training-data knowledge of "today".
 *
 * - [getCurrentDateTime]: Current local date and time in a given timezone.
 * - [convertTimezone]: Convert a datetime from one timezone to another.
 * - [calculateDateDiff]: Number of days between two dates.
 * - [addDaysToDate]: Add or subtract days from a date.
 */
object DateTimeTools {

    private const val CLASS_NAME = "io.askimo.tools.datetime.DateTimeTools"

    private val isoDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    @Tool(
        """Get the current date and time for a given timezone (or the system local timezone).

Use this tool when the user asks about:
- What is today's date?
- What time is it now (in a city / timezone)?
- What day of the week is today?
- Current date and time in any location

TIMEZONE PARAMETER:
- Accept IANA timezone IDs: "America/New_York", "Europe/London", "Asia/Tokyo", "UTC"
- Also accept common abbreviations: "PST", "EST", "GMT", "IST" (mapped internally)
- If the user does not specify a timezone, pass an empty string — the system local timezone will be used.

OUTPUT FORMAT:
Present the date and time in a human-friendly format. Include:
- Full date (e.g., Monday, April 7, 2026)
- Time with timezone abbreviation (e.g., 14:35 JST)
- Day of the week
""",
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"getCurrentDateTime\" }",
    )
    fun getCurrentDateTime(
        @P("IANA timezone ID (e.g. 'America/New_York', 'Asia/Tokyo', 'UTC'). Leave empty for system local time.")
        timezone: String = "",
    ): String = try {
        val zone = resolveZone(timezone)
        val now = ZonedDateTime.now(zone)
        val dateStr = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH))
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH))
        val tzAbbr = now.zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        val tzFull = now.zone.id

        ToolResponseBuilder.successWithData(
            output = buildString {
                appendLine("Current date and time:")
                appendLine("Date: $dateStr")
                appendLine("Time: $timeStr $tzAbbr ($tzFull)")
                appendLine("Day of week: ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}")
                appendLine("ISO: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            }.trim(),
            data = mapOf(
                "date" to now.toLocalDate().toString(),
                "time" to timeStr,
                "datetime_iso" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "day_of_week" to now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                "timezone_id" to tzFull,
                "timezone_abbr" to tzAbbr,
                "year" to now.year,
                "month" to now.monthValue,
                "day" to now.dayOfMonth,
                "hour" to now.hour,
                "minute" to now.minute,
            ),
        )
    } catch (e: Exception) {
        ToolResponseBuilder.failure(
            error = "Could not get current date/time: ${e.message}",
            metadata = mapOf("timezone" to timezone),
        )
    }

    @Tool(
        """Convert a date and time from one timezone to another.

Use this tool when the user asks:
- "What time is 3pm New York in London?"
- "Convert 09:00 Tokyo time to UTC"
- "If it's 14:00 in Paris, what time is it in Los Angeles?"

PARAMETERS:
- datetime: The date and time to convert, in format "yyyy-MM-dd HH:mm" or "yyyy-MM-dd HH:mm:ss"
- fromTimezone: Source IANA timezone ID (e.g. "America/New_York")
- toTimezone: Target IANA timezone ID (e.g. "Europe/London")

OUTPUT FORMAT:
Show both the original and converted datetime clearly with their timezone names.
""",
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"convertTimezone\" }",
    )
    fun convertTimezone(
        @P("Date and time to convert, e.g. '2026-04-07 14:30' or '2026-04-07 14:30:00'")
        datetime: String,
        @P("Source IANA timezone ID, e.g. 'America/New_York', 'UTC', 'Asia/Tokyo'")
        fromTimezone: String,
        @P("Target IANA timezone ID, e.g. 'Europe/London', 'Asia/Singapore'")
        toTimezone: String,
    ): String = try {
        val fromZone = resolveZone(fromTimezone)
        val toZone = resolveZone(toTimezone)

        val localDt = parseDateTime(datetime)
            ?: return ToolResponseBuilder.failure(
                error = "Could not parse datetime: \"$datetime\". Use format 'yyyy-MM-dd HH:mm' or 'yyyy-MM-dd HH:mm:ss'.",
                metadata = mapOf("datetime" to datetime, "fromTimezone" to fromTimezone, "toTimezone" to toTimezone),
            )

        val fromZoned = ZonedDateTime.of(localDt, fromZone)
        val toZoned = fromZoned.withZoneSameInstant(toZone)

        val fmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' HH:mm:ss", Locale.ENGLISH)

        ToolResponseBuilder.successWithData(
            output = buildString {
                appendLine("Timezone conversion result:")
                appendLine("From: ${fromZoned.format(fmt)} ${fromZone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} (${fromZone.id})")
                appendLine("To:   ${toZoned.format(fmt)} ${toZone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} (${toZone.id})")
            }.trim(),
            data = mapOf(
                "original_datetime" to fromZoned.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "converted_datetime" to toZoned.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "from_timezone" to fromZone.id,
                "to_timezone" to toZone.id,
                "from_date" to fromZoned.toLocalDate().toString(),
                "to_date" to toZoned.toLocalDate().toString(),
                "from_time" to fromZoned.toLocalTime().toString(),
                "to_time" to toZoned.toLocalTime().toString(),
            ),
        )
    } catch (e: Exception) {
        ToolResponseBuilder.failure(
            error = "Timezone conversion failed: ${e.message}",
            metadata = mapOf("datetime" to datetime, "fromTimezone" to fromTimezone, "toTimezone" to toTimezone),
        )
    }

    @Tool(
        """Calculate the number of days between two dates.

Use this tool when the user asks:
- "How many days until Christmas / New Year / my birthday?"
- "How many days between date A and date B?"
- "How long ago was [date]?"
- "How many days until / since [event]?"

PARAMETERS:
- fromDate: Start date in ISO format "yyyy-MM-dd"
- toDate: End date in ISO format "yyyy-MM-dd"

If the user asks about days until/since a future/past event without knowing "today",
first call getCurrentDateTime to get today's date, then call this tool.

OUTPUT FORMAT:
State the number of days clearly. For large numbers also show years and months.
""",
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"calculateDateDiff\" }",
    )
    fun calculateDateDiff(
        @P("Start date in ISO format, e.g. '2026-01-01'")
        fromDate: String,
        @P("End date in ISO format, e.g. '2026-12-31'")
        toDate: String,
    ): String = try {
        val from = LocalDate.parse(fromDate, isoDateFormatter)
        val to = LocalDate.parse(toDate, isoDateFormatter)

        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to)
        val absDays = Math.abs(days)
        val direction = when {
            days > 0 -> "after"
            days < 0 -> "before"
            else -> "same day"
        }

        val years = absDays / 365
        val remainingDays = absDays % 365
        val months = remainingDays / 30
        val leftoverDays = remainingDays % 30

        val humanReadable = buildString {
            if (days == 0L) {
                append("same day")
            } else {
                if (years > 0) append("$years year${if (years != 1L) "s" else ""}")
                if (years > 0 && months > 0) append(", ")
                if (months > 0) append("$months month${if (months != 1L) "s" else ""}")
                if ((years > 0 || months > 0) && leftoverDays > 0) append(", ")
                if (leftoverDays > 0 || (years == 0L && months == 0L)) append("$leftoverDays day${if (leftoverDays != 1L) "s" else ""}")
                append(" $direction")
            }
        }

        ToolResponseBuilder.successWithData(
            output = buildString {
                appendLine("Date difference: $from → $to")
                appendLine("Difference: $days day${if (Math.abs(days) != 1L) "s" else ""} ($humanReadable)")
            }.trim(),
            data = mapOf(
                "from_date" to fromDate,
                "to_date" to toDate,
                "days" to days,
                "absolute_days" to absDays,
                "direction" to direction,
                "human_readable" to humanReadable,
            ),
        )
    } catch (_: DateTimeParseException) {
        ToolResponseBuilder.failure(
            error = "Invalid date format. Use ISO format 'yyyy-MM-dd', e.g. '2026-04-07'.",
            metadata = mapOf("fromDate" to fromDate, "toDate" to toDate),
        )
    } catch (e: Exception) {
        ToolResponseBuilder.failure(
            error = "Date difference calculation failed: ${e.message}",
            metadata = mapOf("fromDate" to fromDate, "toDate" to toDate),
        )
    }

    @Tool(
        """Add or subtract a number of days from a given date and return the resulting date.

Use this tool when the user asks:
- "What date is 30 days from today?"
- "What was the date 90 days ago?"
- "What date is 2 weeks from [date]?"

PARAMETERS:
- date: A date in ISO format "yyyy-MM-dd"
- days: Number of days to add (positive) or subtract (negative)

If the user says "from today" or "from now", first call getCurrentDateTime to get today's date.

OUTPUT FORMAT:
Show the input date, the offset, and the resulting date clearly.
""",
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"addDaysToDate\" }",
    )
    fun addDaysToDate(
        @P("Base date in ISO format, e.g. '2026-04-07'")
        date: String,
        @P("Number of days to add (positive) or subtract (negative)")
        days: Long,
    ): String = try {
        val base = LocalDate.parse(date, isoDateFormatter)
        val result = base.plusDays(days)

        val action = if (days >= 0) {
            "Adding $days day${if (days != 1L) "s" else ""} to"
        } else {
            "Subtracting ${-days} day${if (-days != 1L) "s" else ""} from"
        }
        val fmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)

        ToolResponseBuilder.successWithData(
            output = buildString {
                appendLine("$action $date:")
                appendLine("Result: ${result.format(fmt)} ($result)")
            }.trim(),
            data = mapOf(
                "input_date" to date,
                "days_added" to days,
                "result_date" to result.toString(),
                "result_day_of_week" to result.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            ),
        )
    } catch (_: DateTimeParseException) {
        ToolResponseBuilder.failure(
            error = "Invalid date format. Use ISO format 'yyyy-MM-dd', e.g. '2026-04-07'.",
            metadata = mapOf("date" to date, "days" to days),
        )
    } catch (e: Exception) {
        ToolResponseBuilder.failure(
            error = "Date calculation failed: ${e.message}",
            metadata = mapOf("date" to date, "days" to days),
        )
    }

    /**
     * Resolves a timezone string to a [ZoneId].
     * Accepts IANA IDs ("America/New_York"), UTC offsets ("+05:30"),
     * and common abbreviations mapped to unambiguous IANA IDs.
     */
    private fun resolveZone(timezone: String): ZoneId {
        if (timezone.isBlank()) return ZoneId.systemDefault()
        val mapped = TIMEZONE_ALIASES[timezone.uppercase()] ?: timezone
        return try {
            ZoneId.of(mapped)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
    }

    private fun parseDateTime(datetime: String): LocalDateTime? {
        val formats = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        )
        for (fmt in formats) {
            try {
                return LocalDateTime.parse(datetime.trim(), fmt)
            } catch (_: Exception) {}
        }
        return null
    }

    private val TIMEZONE_ALIASES = mapOf(
        "UTC" to "UTC",
        "GMT" to "GMT",
        "EST" to "America/New_York",
        "EDT" to "America/New_York",
        "CST" to "America/Chicago",
        "CDT" to "America/Chicago",
        "MST" to "America/Denver",
        "MDT" to "America/Denver",
        "PST" to "America/Los_Angeles",
        "PDT" to "America/Los_Angeles",
        "IST" to "Asia/Kolkata",
        "JST" to "Asia/Tokyo",
        "AEST" to "Australia/Sydney",
        "AEDT" to "Australia/Sydney",
        "CET" to "Europe/Paris",
        "CEST" to "Europe/Paris",
        "BST" to "Europe/London",
        "WET" to "Europe/Lisbon",
        "EET" to "Europe/Helsinki",
        "MSK" to "Europe/Moscow",
        "HKT" to "Asia/Hong_Kong",
        "SGT" to "Asia/Singapore",
        "ICT" to "Asia/Bangkok",
        "WIB" to "Asia/Jakarta",
        "PKT" to "Asia/Karachi",
        "NPT" to "Asia/Kathmandu",
        "BDT" to "Asia/Dhaka",
        "NZST" to "Pacific/Auckland",
        "NZDT" to "Pacific/Auckland",
        "HST" to "Pacific/Honolulu",
        "AKST" to "America/Anchorage",
        "BRT" to "America/Sao_Paulo",
        "ART" to "America/Argentina/Buenos_Aires",
        "CLT" to "America/Santiago",
        "COT" to "America/Bogota",
        "PET" to "America/Lima",
        "VET" to "America/Caracas",
        "AST" to "America/Halifax",
        "CAT" to "Africa/Harare",
        "EAT" to "Africa/Nairobi",
        "WAT" to "Africa/Lagos",
    )
}
