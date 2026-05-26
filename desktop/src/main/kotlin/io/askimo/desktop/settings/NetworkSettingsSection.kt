/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.settings

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.config.AppConfig
import io.askimo.core.config.ProxyType
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import java.awt.Desktop
import java.net.URI

@Composable
fun networkSettingsSection() {
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
                    text = stringResource("settings.network"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                Text(
                    text = stringResource("settings.network.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )

                // Proxy Configuration Card
                proxyConfigurationCard()
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = MaterialTheme.shapes.small,
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            ),
        )
    }
}

@Composable
private fun proxyConfigurationCard() {
    var proxyType by remember { mutableStateOf(AppConfig.proxy.type) }
    var proxyHost by remember { mutableStateOf(AppConfig.proxy.host) }
    var proxyPort by remember { mutableStateOf(AppConfig.proxy.port.toString()) }
    var proxyUsername by remember { mutableStateOf(AppConfig.proxy.username) }
    var proxyPassword by remember { mutableStateOf(AppConfig.proxy.password) }
    var proxyTypeDropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppComponents.bannerCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.proxy.title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                linkButton(
                    onClick = {
                        try {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/proxy-configuration/"))
                            }
                        } catch (_: Exception) {}
                    },
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource("settings.proxy.guide"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            Text(
                text = stringResource("settings.proxy.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )

            HorizontalDivider()

            // Proxy Type Dropdown
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = stringResource("settings.proxy.type"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )

                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableCard { proxyTypeDropdownExpanded = true },
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource("settings.proxy.type.${proxyType.name.lowercase()}"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource("settings.proxy.type.${proxyType.name.lowercase()}.description"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change proxy type",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = proxyTypeDropdownExpanded,
                        onDismissRequest = { proxyTypeDropdownExpanded = false },
                    ) {
                        ProxyType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = stringResource("settings.proxy.type.${type.name.lowercase()}"),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = stringResource("settings.proxy.type.${type.name.lowercase()}.description"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    proxyType = type
                                    AppConfig.updateField("proxy.type", type.name)
                                    proxyTypeDropdownExpanded = false
                                },
                                leadingIcon = if (type == proxyType) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            // Show configuration fields only for HTTP, HTTPS, or SOCKS5
            if (proxyType != ProxyType.NONE && proxyType != ProxyType.SYSTEM) {
                HorizontalDivider()

                // Proxy Host
                OutlinedTextField(
                    value = proxyHost,
                    onValueChange = { newValue ->
                        proxyHost = newValue
                        AppConfig.updateField("proxy.host", newValue)
                    },
                    label = { Text(stringResource("settings.proxy.host")) },
                    placeholder = { Text("proxy.company.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Proxy Port
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = { newValue ->
                        proxyPort = newValue
                        newValue.toIntOrNull()?.let { port ->
                            AppConfig.updateField("proxy.port", port)
                        }
                    },
                    label = { Text(stringResource("settings.proxy.port")) },
                    placeholder = { Text("8080") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Show authentication fields only for HTTP/HTTPS (not SOCKS5)
                // SOCKS5 authentication is not supported by java.net.http.HttpClient
                if (proxyType == ProxyType.HTTP || proxyType == ProxyType.HTTPS) {
                    HorizontalDivider()

                    Text(
                        text = stringResource("settings.proxy.authentication"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )

                    Text(
                        text = stringResource("settings.proxy.authentication.description"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )

                    // Username (Optional)
                    OutlinedTextField(
                        value = proxyUsername,
                        onValueChange = { newValue ->
                            proxyUsername = newValue
                            AppConfig.updateField("proxy.username", newValue)
                        },
                        label = { Text(stringResource("settings.proxy.username")) },
                        placeholder = { Text(stringResource("settings.proxy.username.placeholder")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    // Password (Optional)
                    OutlinedTextField(
                        value = proxyPassword,
                        onValueChange = { newValue ->
                            proxyPassword = newValue
                            AppConfig.updateField("proxy.password", newValue)
                        },
                        label = { Text(stringResource("settings.proxy.password")) },
                        placeholder = { Text(stringResource("settings.proxy.password.placeholder")) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else if (proxyType == ProxyType.SOCKS5) {
                    // Show info that SOCKS5 authentication is not supported
                    HorizontalDivider()

                    Text(
                        text = stringResource("settings.proxy.socks5.no.auth.info"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // Show info for SYSTEM proxy type
            if (proxyType == ProxyType.SYSTEM) {
                HorizontalDivider()

                Text(
                    text = stringResource("settings.proxy.system.info"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
