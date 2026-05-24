/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.IDateColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Custom Exposed column type that stores [Instant] values in SQLite as UTC ISO-8601 TEXT.
 *
 * Format on disk: `2026-05-24T10:15:30.123456Z`
 *
 * Reading is tolerant of:
 * - ISO-8601 with Z suffix (canonical form written by this type)
 * - ISO-8601 with explicit offset (e.g. `+00:00`)
 * - Legacy `LocalDateTime`-style strings without offset (assumed UTC)
 * - Space-separator format written by older Exposed implementations
 * - Epoch-millisecond longs (produced by some JDBC drivers)
 */
class SQLiteInstantColumnType :
    ColumnType<Instant>(),
    IDateColumnType {

    override val hasTimePart: Boolean = true

    override fun sqlType(): String = currentDialect.dataTypeProvider.dateTimeType()

    override fun nonNullValueToString(value: Instant): String = "'${FORMATTER.format(value)}'"

    override fun notNullValueToDB(value: Instant): Any = FORMATTER.format(value)

    override fun valueFromDB(value: Any): Instant? = when (value) {
        is Instant -> value
        is Long -> Instant.ofEpochMilli(value)
        is Int -> Instant.ofEpochMilli(value.toLong())
        is String -> parseInstant(value)
        else -> parseInstant(value.toString())
    }

    private fun parseInstant(raw: String): Instant = runCatching {
        // Canonical form: ends with Z or explicit offset
        Instant.parse(raw)
    }.getOrElse {
        runCatching {
            // Space-separator legacy format: "2026-05-24 10:15:30.123456" — treat as UTC
            val normalized = raw.replace(' ', 'T')
            val withZ = if (normalized.endsWith('Z') || normalized.contains('+')) normalized else "${normalized}Z"
            Instant.parse(withZ)
        }.getOrElse {
            // Last resort: parse as LocalDateTime at UTC
            LocalDateTime.parse(raw, LEGACY_FORMATTER).toInstant(ZoneOffset.UTC)
        }
    }

    companion object {
        /** Canonical ISO-8601 UTC formatter — always writes the `Z` suffix. */
        val FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

        private val LEGACY_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSSSSSSSS][.SSSSSS][.SSS]")
    }
}

/**
 * Extension function to create a SQLite-compatible [Instant] column.
 * Use this for all sync-critical timestamp columns.
 */
fun Table.sqliteInstant(name: String): Column<Instant> = registerColumn(name, SQLiteInstantColumnType())
