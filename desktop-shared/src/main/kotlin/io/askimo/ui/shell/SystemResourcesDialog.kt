/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.monitoring.SystemResourceMonitor
import io.askimo.ui.common.theme.Spacing
import org.koin.java.KoinJavaComponent.get

/**
 * Dialog that surfaces live system-resource metrics (CPU, JVM memory) and a
 * caller-supplied telemetry panel.
 *
 * The [telemetryContent] slot is intentionally left open so that each module
 * can pass its own `telemetryPanel` composable (community vs. Pro flavours
 * differ in implementation but share this outer shell).
 *
 * @param onExportTelemetry Called when the user clicks "Export CSV". The caller
 *   is responsible for picking a save path and invoking the telemetry export service.
 *   Pass `null` to hide the export button (e.g. when no telemetry data exists).
 */
@Composable
fun systemResourcesDialog(
    onDismiss: () -> Unit,
    onExportTelemetry: (() -> Unit)?,
    telemetryContent: @Composable () -> Unit,
) {
    val monitor = remember { get<SystemResourceMonitor>(SystemResourceMonitor::class.java) }

    val memoryUsage by monitor.memoryUsageMB.collectAsState()
    val cpuUsage by monitor.cpuUsagePercent.collectAsState()

    // Keep the monitor ticking while the dialog is open.
    LaunchedEffect(monitor) {
        monitor.startMonitoring(intervalMillis = 1000)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(900.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(Spacing.extraLarge)) {
                // ── Header ──────────────────────────────────────────────────
                Text(
                    text = stringResource("system.diagnostics.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(Spacing.large))

                // ── Resources section ────────────────────────────────────
                Text(
                    text = stringResource("system.diagnostics.resources"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(Spacing.medium))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.large),
                ) {
                    resourceMetricCard(
                        icon = {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = stringResource("system.diagnostics.jvm.memory"),
                        value = "$memoryUsage MB",
                        modifier = Modifier.weight(1f),
                    )
                    resourceMetricCard(
                        icon = {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = stringResource("system.diagnostics.cpu.usage"),
                        value = "%.1f%%".format(cpuUsage),
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(Spacing.large))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.large))

                // ── Telemetry section ────────────────────────────────────
                Text(
                    text = stringResource("system.diagnostics.telemetry"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(Spacing.small))

                telemetryContent()

                Spacer(Modifier.height(Spacing.large))

                // ── Action buttons ────────────────────────────────────────
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    if (onExportTelemetry != null) {
                        secondaryButton(onClick = onExportTelemetry) {
                            Text(stringResource("telemetry.export.csv"))
                        }
                    }
                    secondaryButton(onClick = onDismiss) {
                        Text(stringResource("action.close"))
                    }
                }
            }
        }
    }
}

@Composable
private fun resourceMetricCard(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            icon()
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
