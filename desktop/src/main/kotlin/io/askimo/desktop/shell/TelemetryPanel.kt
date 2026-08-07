/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.askimo.core.context.AppContext
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.telemetry.LlmInstanceStats
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.util.formatDuration
import io.askimo.ui.util.formatDurationDetailed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.get
import java.time.Instant
import java.util.Locale.getDefault

/**
 * Telemetry panel showing RAG and LLM metrics.
 * Max height is limited to 1/3 of parent height with scrolling support.
 */
@Composable
internal fun telemetryPanel(maxHeight: Dp) {
    val appContext = remember { get<AppContext>(AppContext::class.java) }
    val telemetry = appContext.telemetry
    val refreshSignal by telemetry.refreshSignal.collectAsState()
    var stats by remember { mutableStateOf<List<LlmInstanceStats>>(emptyList()) }

    LaunchedEffect(refreshSignal) {
        stats = withContext(Dispatchers.IO) {
            telemetry.usageRepository.queryGroupedByInstance(telemetry.sessionStart, Instant.now())
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        val scrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                // Header with title and reset button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("telemetry.title"),
                        style = AppTextStyles.itemTitle,
                    )

                    if (stats.isNotEmpty()) {
                        themedTooltip(text = stringResource("telemetry.reset")) {
                            IconButton(
                                onClick = { telemetry.reset() },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource("telemetry.reset"),
                                    modifier = Modifier.size(16.dp).pointerHoverIcon(PointerIcon.Hand),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                if (stats.isEmpty()) {
                    Text(
                        text = stringResource("telemetry.no.data"),
                        style = AppTextStyles.bodySecondary,
                        modifier = Modifier.padding(vertical = Spacing.small),
                    )
                    return@Column
                }

                // ── LLM section ───────────────────────────────────────────
                Text(
                    text = stringResource("telemetry.tab.llm"),
                    style = AppTextStyles.fieldLabel,
                    fontWeight = FontWeight.SemiBold,
                )

                var sortColumn by remember { mutableStateOf(LlmSortColumn.INSTANCE) }
                var sortAscending by remember { mutableStateOf(true) }

                fun toggleSort(column: LlmSortColumn) {
                    if (sortColumn == column) {
                        sortAscending = !sortAscending
                    } else {
                        sortColumn = column
                        sortAscending = true
                    }
                }

                llmTableHeader(
                    sortColumn = sortColumn,
                    sortAscending = sortAscending,
                    onSort = ::toggleSort,
                )

                HorizontalDivider()

                var totalCalls = 0
                var totalTokens = 0L
                var totalErrors = 0

                val rows = stats.map { stat ->
                    val instance = stat.instanceKey
                        .split(":", limit = 2).getOrElse(0) { stat.instanceKey }
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }
                    LlmRow(instance, stat.model, stat.calls, stat.tokens, stat.avgDurationMs, stat.errors)
                }

                val sorted = when (sortColumn) {
                    LlmSortColumn.INSTANCE -> rows.sortedBy { it.instance }
                    LlmSortColumn.MODEL -> rows.sortedBy { it.model }
                    LlmSortColumn.CALLS -> rows.sortedBy { it.calls }
                    LlmSortColumn.TOKENS -> rows.sortedBy { it.tokens }
                    LlmSortColumn.AVG_DURATION -> rows.sortedBy { it.avgDurationMs }
                    LlmSortColumn.ERRORS -> rows.sortedBy { it.errors }
                }.let { if (sortAscending) it else it.reversed() }

                sorted.forEach { row ->
                    totalCalls += row.calls
                    totalTokens += row.tokens
                    totalErrors += row.errors
                    llmTableDataRow(row)
                }

                HorizontalDivider()

                llmTableRow(
                    instance = stringResource("telemetry.llm.col.total"),
                    model = "",
                    calls = LocalizationManager.formatNumber(totalCalls),
                    tokens = LocalizationManager.formatNumber(totalTokens),
                    avgDuration = "",
                    errors = if (totalErrors > 0) LocalizationManager.formatNumber(totalErrors) else "—",
                    isHeader = true,
                    errorsIsError = totalErrors > 0,
                )
            }

            VerticalScrollbar(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = Spacing.extraSmall),
                adapter = rememberScrollbarAdapter(scrollState),
                style = AppComponents.scrollbarStyle(),
            )
        }
    }
}

private enum class LlmSortColumn { INSTANCE, MODEL, CALLS, TOKENS, AVG_DURATION, ERRORS }

private data class LlmRow(
    val instance: String,
    val model: String,
    val calls: Int,
    val tokens: Long,
    val avgDurationMs: Long,
    val errors: Int,
)

// Column weights — single source of truth so header and data rows always align
private val COL_PROVIDER = 1.4f
private val COL_MODEL = 1.8f
private val COL_CALLS = 0.8f
private val COL_TOKENS = 1.1f
private val COL_DURATION = 1.1f
private val COL_ERRORS = 0.7f

@Composable
private fun llmTableHeader(
    sortColumn: LlmSortColumn,
    sortAscending: Boolean,
    onSort: (LlmSortColumn) -> Unit,
) {
    val headerStyle = AppTextStyles.hint
    val activeColor = MaterialTheme.colorScheme.onSurface
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    val cols = listOf(
        Triple(stringResource("telemetry.llm.col.instance"), LlmSortColumn.INSTANCE, COL_PROVIDER),
        Triple(stringResource("telemetry.llm.col.model"), LlmSortColumn.MODEL, COL_MODEL),
        Triple(stringResource("telemetry.llm.col.calls"), LlmSortColumn.CALLS, COL_CALLS),
        Triple(stringResource("telemetry.llm.col.tokens"), LlmSortColumn.TOKENS, COL_TOKENS),
        Triple(stringResource("telemetry.llm.col.avg.duration"), LlmSortColumn.AVG_DURATION, COL_DURATION),
        Triple(stringResource("telemetry.llm.col.errors"), LlmSortColumn.ERRORS, COL_ERRORS),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cols.forEach { (label, column, weight) ->
            val isActive = sortColumn == column
            Row(
                modifier = Modifier
                    .weight(weight)
                    .clickable { onSort(column) }
                    .pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = headerStyle,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) activeColor else inactiveColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Icon(
                        imageVector = if (sortAscending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = activeColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun llmTableDataRow(row: LlmRow) {
    val style = AppTextStyles.caption
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = row.instance, style = style, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(COL_PROVIDER), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = row.model, style = style, color = secondary, modifier = Modifier.weight(COL_MODEL), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = LocalizationManager.formatNumber(row.calls), style = style, color = secondary, modifier = Modifier.weight(COL_CALLS), maxLines = 1)
        Text(text = LocalizationManager.formatNumber(row.tokens), style = style, color = secondary, modifier = Modifier.weight(COL_TOKENS), maxLines = 1)
        Box(modifier = Modifier.weight(COL_DURATION)) {
            themedTooltip(text = formatDurationDetailed(row.avgDurationMs)) {
                Text(text = formatDuration(row.avgDurationMs), style = style, color = secondary, maxLines = 1)
            }
        }
        Text(
            text = if (row.errors > 0) LocalizationManager.formatNumber(row.errors) else "—",
            style = style,
            color = if (row.errors > 0) MaterialTheme.colorScheme.error else secondary,
            modifier = Modifier.weight(COL_ERRORS),
            maxLines = 1,
        )
    }
}

@Composable
private fun llmTableRow(
    instance: String,
    model: String,
    calls: String,
    tokens: String,
    avgDuration: String,
    errors: String,
    isHeader: Boolean = false,
    errorsIsError: Boolean = false,
) {
    val style = if (isHeader) AppTextStyles.hint else AppTextStyles.caption
    val fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (isHeader) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = instance, style = style, fontWeight = fontWeight, color = defaultColor, modifier = Modifier.weight(COL_PROVIDER), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = model, style = style, fontWeight = fontWeight, color = secondaryColor, modifier = Modifier.weight(COL_MODEL), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = calls, style = style, fontWeight = fontWeight, color = secondaryColor, modifier = Modifier.weight(COL_CALLS), maxLines = 1)
        Text(text = tokens, style = style, fontWeight = fontWeight, color = secondaryColor, modifier = Modifier.weight(COL_TOKENS), maxLines = 1)
        Text(text = avgDuration, style = style, fontWeight = fontWeight, color = secondaryColor, modifier = Modifier.weight(COL_DURATION), maxLines = 1)
        Text(
            text = errors,
            style = style,
            fontWeight = fontWeight,
            color = if (errorsIsError) MaterialTheme.colorScheme.error else secondaryColor,
            modifier = Modifier.weight(COL_ERRORS),
            maxLines = 1,
        )
    }
}

@Composable
private fun telemetryStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = AppTextStyles.body,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = AppTextStyles.hint,
        )
    }
}

@Composable
private fun telemetryMetricCard(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    valueTooltip: String? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            if (valueTooltip != null) {
                themedTooltip(text = valueTooltip) {
                    Text(
                        text = value,
                        style = AppTextStyles.sectionTitle,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Text(
                    text = value,
                    style = AppTextStyles.sectionTitle,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = label,
                style = AppTextStyles.hint,
            )
            Text(
                text = subtitle,
                style = AppTextStyles.caption,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
