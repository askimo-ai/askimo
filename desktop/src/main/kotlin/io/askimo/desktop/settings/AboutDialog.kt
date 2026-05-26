/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.VersionInfo
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import java.awt.Desktop
import java.net.URI
import java.time.Year

@Composable
fun aboutDialog(
    onDismiss: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource("about.title", VersionInfo.name),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                // Version Info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.bannerCardColors(),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = VersionInfo.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = "${stringResource("about.version")} ${VersionInfo.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = "${stringResource("about.buildDate")}: ${VersionInfo.buildDate}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        linkButton(
                            onClick = {
                                try {
                                    Desktop.getDesktop().browse(URI("https://$DOMAIN"))
                                } catch (_: Exception) {
                                    // Silently fail if browser cannot be opened
                                }
                            },
                        ) {
                            Text(
                                text = "https://$DOMAIN",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                // Description
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.bannerCardColors(),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("about.description"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource("about.description.text"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                // License
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppComponents.bannerCardColors(),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("about.license"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = VersionInfo.license,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource("about.copyright", Year.now().value, VersionInfo.author),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        },
        confirmButton = {
            secondaryButton(
                onClick = onDismiss,
            ) {
                Text(stringResource("action.close"))
            }
        },
    )
}
