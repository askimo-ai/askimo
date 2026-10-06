/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import app.cash.sqldelight.ColumnAdapter
import io.askimo.core.util.TimeUtil
import kotlin.time.Instant

/**
 * Maps a SQLDelight `TEXT` column directly to [kotlin.time.Instant].
 *
 * - [decode] reuses [TimeUtil.parseInstant], so every format this codebase has ever written
 *   (canonical `Z`-suffixed ISO-8601, offset-free ISO-8601, legacy space-separated datetime)
 *   keeps working — the UTC assumption for offset-free/legacy values lives in exactly one place.
 * - [encode] always writes the canonical, unambiguous `Z`-suffixed ISO-8601 form
 *   (`Instant.toString()`), so every *new* write is lossless and never falls back into the
 *   offset-free ambiguity again.
 */
object InstantColumnAdapter : ColumnAdapter<Instant, String> {
    override fun decode(databaseValue: String): Instant = TimeUtil.parseInstant(databaseValue)

    override fun encode(value: Instant): String = value.toString()
}
