/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import io.askimo.core.db.sqldelight.generated.AskimoDatabase

abstract class AbstractRepository(
    private val databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) {
    protected val db: AskimoDatabase get() = databaseManager.db
}
