/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource
import kotlin.getValue

abstract class AbstractSQLiteRepository(
    private val databaseManager: DatabaseManager = DatabaseManager.getInstance(),
) {

    protected val dataSource: DataSource get() = databaseManager.dataSource

    protected val database: Database by lazy {
        Database.connect(dataSource)
    }

    /**
     * Generic helper method to query entities by a list of values for a specific column.
     *
     * This method provides a reusable pattern for "WHERE column IN (values)" queries
     * with automatic mapping to entity objects. Useful for batch lookups by ID, name, etc.
     *
     * @param table The Exposed table to query from
     * @param column The column to filter by
     * @param values The list of values to match against the column
     * @param orderBy Optional ordering specification (column to sort order)
     * @param mapper Function to map a ResultRow to an entity object
     * @return List of entities matching the query, or empty list if values is empty
     *
     * @sample
     * ```kotlin
     * fun getByIds(ids: List<String>): List<MyEntity> =
     *     getByColumn(MyTable, MyTable.id, ids) { row ->
     *         MyEntity(
     *             id = row[MyTable.id],
     *             name = row[MyTable.name]
     *         )
     *     }
     * ```
     */
    protected fun <T, E> getByColumn(
        table: Table,
        column: Column<T>,
        values: List<T>,
        orderBy: Pair<Column<*>, SortOrder>? = null,
        mapper: (ResultRow) -> E,
    ): List<E> {
        if (values.isEmpty()) return emptyList()

        return transaction(database) {
            val query = table.selectAll().where { column inList values }
            if (orderBy != null) {
                query.orderBy(orderBy.first to orderBy.second)
            }
            query.map(mapper)
        }
    }
}
