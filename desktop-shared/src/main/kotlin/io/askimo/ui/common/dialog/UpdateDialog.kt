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
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
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
        modifier = Modifier.widthIn(min = 800.dp, max = 900.dp),
        title = {
            Text(
                text = stringResource("update.dialog.title"),
                style = AppTextStyles.pageTitle,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
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

                    UpdateUrgency.LOW -> AppColors.cardColors(AppColors.Elevation.ACCENT)
                }
                val bannerIcon = if (urgency == UpdateUrgency.HIGH) Icons.Default.Warning else Icons.Default.Info
                val bannerText = when (urgency) {
                    UpdateUrgency.HIGH -> stringResource("update.urgency.high", behindLabel)
                    UpdateUrgency.MEDIUM -> stringResource("update.urgency.medium", behindLabel)
                    UpdateUrgency.LOW -> stringResource("update.dialog.new.version.available")
                }

                Card(modifier = Modifier.fillMaxWidth(), colors = bannerColors) {
                    Row(
                        modifier = Modifier.padding(Spacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = bannerIcon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = bannerText,
                            style = AppTextStyles.body,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Version info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = stringResource("update.dialog.current.version.label"),
                                style = AppTextStyles.hint,
                            )
                            Text(
                                text = currentVersion,
                                style = AppTextStyles.body,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource("update.dialog.new.version.label"),
                                style = AppTextStyles.hint,
                            )
                            Text(
                                text = releaseInfo.latestVersion,
                                style = AppTextStyles.body,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // Release info
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                    Text(
                        text = stringResource("update.dialog.release.date"),
                        style = AppTextStyles.hint,
                    )
                    Text(
                        text = releaseInfo.releaseDate,
                        style = AppTextStyles.body,
                    )
                }

                // Release notes
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                    Text(
                        text = stringResource("update.dialog.release.notes"),
                        style = AppTextStyles.hint,
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.medium),
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                linkButton(onClick = onHowToUpdate) {
                    Text(stringResource("update.dialog.how.to.update"))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
                ) {
                    secondaryButton(onClick = onLater) {
                        Text(stringResource("update.dialog.later"))
                    }
                    secondaryButton(onClick = onSkipVersion) {
                        Text(stringResource("update.dialog.skip.version"))
                    }
                    primaryButton(onClick = onDownload) {
                        Text(stringResource("update.dialog.download"))
                    }
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
                style = AppTextStyles.pageTitle,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Text(
                    text = stringResource("update.check.up.to.date"),
                    style = AppTextStyles.body,
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    ) {
                        Text(
                            text = stringResource("update.dialog.current.version.label"),
                            style = AppTextStyles.hint,
                        )
                        Text(
                            text = currentVersion,
                            style = AppTextStyles.body,
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
                style = AppTextStyles.pageTitle,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
            ) {
                Text(
                    text = message,
                    style = AppTextStyles.body,
                    modifier = Modifier.padding(Spacing.large),
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
