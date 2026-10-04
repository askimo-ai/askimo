/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import kotlinx.serialization.json.Json

val appJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}
