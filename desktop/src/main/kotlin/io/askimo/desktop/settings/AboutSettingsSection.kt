/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.VersionInfo
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import java.awt.Desktop
import java.net.URI
import java.time.Year

@Composable
fun aboutSettingsSection() {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 36.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource("settings.about"),
                    style = AppTextStyles.pageTitle,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                // Application Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = AppTextStyles.primaryContent,
                            )
                            Column {
                                Text(
                                    text = VersionInfo.name,

                                    style = AppTextStyles.sectionTitle,
                                )
                                Text(
                                    text = "${stringResource("about.version")} ${VersionInfo.version}",
                                    style = AppTextStyles.body,
                                )
                            }
                        }

                        HorizontalDivider()

                        // Build Information
                        infoRow(
                            icon = Icons.Default.Build,
                            label = stringResource("about.buildDate"),
                            value = VersionInfo.buildDate,
                        )

                        infoRow(
                            icon = Icons.Default.Code,
                            label = stringResource("about.buildJdk"),
                            value = VersionInfo.buildJdk,
                        )

                        // Website Link
                        linkButton(
                            onClick = {
                                openUrl("https://$DOMAIN")
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = DOMAIN,
                                style = AppTextStyles.body,
                            )
                        }
                    }
                }

                // Description Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("about.description"),
                            style = AppTextStyles.sectionTitle,
                        )
                        Text(
                            text = stringResource("about.description.text"),
                            style = AppTextStyles.body,
                        )
                    }
                }

                // License Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("about.license"),
                            style = AppTextStyles.sectionTitle,
                        )
                        Text(
                            text = VersionInfo.license,
                            style = AppTextStyles.body,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(modifier = Modifier.height(Spacing.extraSmall))
                        Text(
                            text = stringResource("about.copyright", Year.now().value, VersionInfo.author),
                            style = AppTextStyles.caption,
                        )
                    }
                }

                // Runtime Information Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("about.runtime.info"),
                            style = AppTextStyles.sectionTitle,
                        )

                        infoRow(
                            icon = Icons.Default.Update,
                            label = "Runtime VM",
                            value = VersionInfo.runtimeVm,
                            useMonospace = true,
                        )

                        infoRow(
                            icon = Icons.Default.Code,
                            label = "Runtime Version",
                            value = VersionInfo.runtimeVersion,
                            useMonospace = true,
                        )
                    }
                }

                // Links Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.cardColors(AppColors.Elevation.ACCENT),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        Text(
                            text = stringResource("about.links"),
                            style = AppTextStyles.sectionTitle,
                        )

                        linkButton(
                            onClick = { openUrl("https://github.com/askimo-ai/askimo") },
                        ) {
                            Text("GitHub Repository")
                        }

                        linkButton(
                            onClick = { openUrl("https://github.com/askimo-ai/askimo/issues") },
                        ) {
                            Text("Report Issues")
                        }

                        linkButton(
                            onClick = { openUrl("https://github.com/askimo-ai/askimo/blob/main/LICENSE") },
                        ) {
                            Text("View License")
                        }
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            style = AppComponents.scrollbarStyle(),
        )
    }
}

@Composable
private fun infoRow(
    icon: ImageVector,
    label: String,
    value: String,
    useMonospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = AppTextStyles.secondaryContent,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = AppTextStyles.hint,
            )
            Text(
                text = value,
                style = AppTextStyles.body,
                fontFamily = if (useMonospace) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

private fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {
        // Silently fail if browser cannot be opened
    }
}
