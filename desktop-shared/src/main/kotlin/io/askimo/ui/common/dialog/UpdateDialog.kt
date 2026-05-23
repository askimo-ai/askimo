/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.askimo.core.service.UpdateChecker.Companion.MAX_VERSIONS_BEHIND_CAP
import io.askimo.core.service.UpdateInfo
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.ui.markdownText

@Composable
fun updateCheckDialog(
    viewModel: io.askimo.ui.shell.UpdateViewModel,
    onDismiss: () -> Unit,
) {
    when {
        viewModel.showUpdateDialog && viewModel.releaseInfo?.isNewVersion == true -> {
            newVersionDialog(
                releaseInfo = viewModel.releaseInfo!!,
                currentVersion = viewModel.getCurrentVersion(),
                onDownload = {
                    viewModel.openDownloadPage()
                    onDismiss()
                },
                onHowToUpdate = {
                    viewModel.openHowToUpdatePage()
                },
                onSkipVersion = {
                    viewModel.skipThisVersion()
                    onDismiss()
                },
                onLater = onDismiss,
            )
        }

        viewModel.releaseInfo != null && !viewModel.releaseInfo!!.isNewVersion -> {
            upToDateDialog(
                currentVersion = viewModel.getCurrentVersion(),
                onDismiss = onDismiss,
            )
        }

        viewModel.errorMessage != null -> {
            errorDialog(
                message = viewModel.errorMessage!!,
                onDismiss = onDismiss,
            )
        }
    }
}

/** Urgency tier derived from [UpdateInfo.versionsBehind]. */
private enum class UpdateUrgency { LOW, MEDIUM, HIGH }

private fun urgencyFor(versionsBehind: Int): UpdateUrgency = when {
    versionsBehind >= 5 -> UpdateUrgency.HIGH
    versionsBehind >= 2 -> UpdateUrgency.MEDIUM
    else -> UpdateUrgency.LOW
}

@Composable
private fun newVersionDialog(
    releaseInfo: UpdateInfo,
    currentVersion: String,
    onDownload: () -> Unit,
    onHowToUpdate: () -> Unit,
    onSkipVersion: () -> Unit,
    onLater: () -> Unit,
) {
    val urgency = urgencyFor(releaseInfo.versionsBehind)
    val cap = MAX_VERSIONS_BEHIND_CAP
    val behindLabel = if (releaseInfo.versionsBehind >= cap) "$cap+" else "${releaseInfo.versionsBehind}"

    AppComponents.alertDialog(
        onDismissRequest = onLater,
        title = {
            Text(
                text = stringResource("update.dialog.title"),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Urgency banner
                val bannerColors = when (urgency) {
                    UpdateUrgency.HIGH -> CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    UpdateUrgency.MEDIUM -> CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )

                    UpdateUrgency.LOW -> AppComponents.bannerCardColors()
                }
                val bannerIcon = if (urgency == UpdateUrgency.HIGH) Icons.Default.Warning else Icons.Default.Info
                val bannerText = when (urgency) {
                    UpdateUrgency.HIGH -> stringResource("update.urgency.high", behindLabel)
                    UpdateUrgency.MEDIUM -> stringResource("update.urgency.medium", behindLabel)
                    UpdateUrgency.LOW -> stringResource("update.dialog.new.version.available")
                }

                Card(modifier = Modifier.fillMaxWidth(), colors = bannerColors) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = bannerIcon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = bannerText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Version info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.bannerCardColors(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = stringResource("update.dialog.current.version.label"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            )
                            Text(
                                text = currentVersion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource("update.dialog.new.version.label"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            )
                            Text(
                                text = releaseInfo.latestVersion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // Release info
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource("update.dialog.release.date"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Text(
                        text = releaseInfo.releaseDate,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Release notes
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource("update.dialog.release.notes"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppComponents.bannerCardColors(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            markdownText(
                                markdown = releaseInfo.releaseNotes,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            primaryButton(onClick = onDownload) {
                Text(stringResource("update.dialog.download"))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                secondaryButton(onClick = onHowToUpdate) {
                    Text(stringResource("update.dialog.how.to.update"))
                }
                secondaryButton(onClick = onSkipVersion) {
                    Text(stringResource("update.dialog.skip.version"))
                }
                secondaryButton(onClick = onLater) {
                    Text(stringResource("update.dialog.later"))
                }
            }
        },
    )
}

@Composable
private fun upToDateDialog(
    currentVersion: String,
    onDismiss: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource("update.dialog.title"),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource("update.check.up.to.date"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.bannerCardColors(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource("update.dialog.current.version.label"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                        Text(
                            text = currentVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        },
        confirmButton = {
            primaryButton(
                onClick = onDismiss,
            ) {
                Text(stringResource("action.ok"))
            }
        },
    )
}

@Composable
private fun errorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource("update.check.failed"),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = AppComponents.bannerCardColors(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
        confirmButton = {
            primaryButton(
                onClick = onDismiss,
            ) {
                Text(stringResource("action.ok"))
            }
        },
    )
}
