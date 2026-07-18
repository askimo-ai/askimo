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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.askimo.core.config.AppConfig
import io.askimo.core.config.WebSearchBackend
import io.askimo.core.config.WebSearchConfig
import io.askimo.tools.web.WebSearchDispatcher
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.clickableCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun webSearchSettingsSection() {
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
                    .padding(start = Spacing.extraLarge, top = Spacing.extraLarge, bottom = Spacing.extraLarge, end = 36.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource("settings.web_search"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                Text(
                    text = stringResource("settings.web_search.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )

                webSearchConfigCard()
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
private fun webSearchConfigCard() {
    var backend by remember { mutableStateOf(AppConfig.rawWebSearch.backend) }
    var enabled by remember { mutableStateOf(AppConfig.rawWebSearch.enabled) }
    var searxngEndpoint by remember { mutableStateOf(AppConfig.rawWebSearch.searxngEndpoint) }
    // API keys loaded async from keychain — start blank
    var braveApiKey by remember { mutableStateOf("") }
    var tavilyApiKey by remember { mutableStateOf("") }
    var backendDropdownExpanded by remember { mutableStateOf(false) }

    var testStatus by remember { mutableStateOf<TestStatus?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Load secured API keys off UI thread — same pattern as proxy password
    LaunchedEffect(Unit) {
        val resolved = withContext(Dispatchers.IO) { AppConfig.webSearch }
        braveApiKey = if (WebSearchConfig.isActualKey(resolved.braveApiKey)) resolved.braveApiKey else ""
        tavilyApiKey = if (WebSearchConfig.isActualKey(resolved.tavilyApiKey)) resolved.tavilyApiKey else ""
    }

    // ── Debounced saves for typed fields (keychain I/O — must NOT block the UI thread) ──
    // Local state updates instantly on every keystroke; the actual persist fires 500 ms
    // after the user stops typing, on Dispatchers.IO.
    LaunchedEffect(braveApiKey) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("webSearch.braveApiKey", braveApiKey) }
    }
    LaunchedEffect(tavilyApiKey) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("webSearch.tavilyApiKey", tavilyApiKey) }
    }
    LaunchedEffect(searxngEndpoint) {
        delay(500.milliseconds)
        withContext(Dispatchers.IO) { AppConfig.updateField("webSearch.searxngEndpoint", searxngEndpoint) }
    }

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
            // ── Header row: title + enabled toggle ───────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.web_search.title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(
                        text = stringResource("settings.web_search.enabled"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            enabled = newValue
                            AppConfig.updateField("webSearch.enabled", newValue)
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }

            Text(
                text = stringResource("settings.web_search.description"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )

            // ── Zero-config info banner ───────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = stringResource("settings.web_search.zero_config_note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            HorizontalDivider()

            // ── Backend selector ──────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = stringResource("settings.web_search.backend"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableCard { backendDropdownExpanded = true },
                        colors = CardDefaults.cardColors(
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
                                    text = stringResource("settings.web_search.backend.${backend.name.lowercase()}"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource("settings.web_search.backend.${backend.name.lowercase()}.description"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change backend",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = backendDropdownExpanded,
                        onDismissRequest = { backendDropdownExpanded = false },
                    ) {
                        val backends = WebSearchBackend.entries
                        backends.forEachIndexed { index, b ->
                            AppComponents.themedDropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                                        Text(
                                            text = stringResource("settings.web_search.backend.${b.name.lowercase()}"),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = stringResource("settings.web_search.backend.${b.name.lowercase()}.description"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    backend = b
                                    AppConfig.updateField("webSearch.backend", b)
                                    backendDropdownExpanded = false
                                    testStatus = null
                                },
                                isSelected = b == backend,
                                showDivider = index < backends.lastIndex,
                            )
                        }
                    }
                }
            }

            // ── Conditional fields by backend ─────────────────────────────────────
            when (backend) {
                WebSearchBackend.BRAVE -> {
                    HorizontalDivider()
                    braveApiKeyField(
                        value = braveApiKey,
                        onValueChange = { newValue ->
                            braveApiKey = newValue
                            testStatus = null
                        },
                    )
                }

                WebSearchBackend.TAVILY -> {
                    HorizontalDivider()
                    tavilyApiKeyField(
                        value = tavilyApiKey,
                        onValueChange = { newValue ->
                            tavilyApiKey = newValue
                            testStatus = null
                        },
                    )
                }

                WebSearchBackend.SEARXNG -> {
                    HorizontalDivider()
                    searxngEndpointField(
                        value = searxngEndpoint,
                        onValueChange = { newValue ->
                            searxngEndpoint = newValue
                            testStatus = null
                        },
                    )
                }

                WebSearchBackend.DUCKDUCKGO -> { /* no extra fields */ }
            }

            HorizontalDivider()

            // ── Test button + result ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primaryButton(
                    onClick = {
                        isTesting = true
                        testStatus = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                WebSearchDispatcher.testBackend(AppConfig.webSearch)
                            }
                            isTesting = false
                            testStatus = if (result.isSuccess) {
                                TestStatus.Success(result.getOrThrow())
                            } else {
                                TestStatus.Failure(result.exceptionOrNull()?.message ?: "Unknown error")
                            }
                        }
                    },
                    enabled = enabled && !isTesting,
                ) {
                    Text(
                        if (isTesting) {
                            stringResource("settings.web_search.test.running")
                        } else {
                            stringResource("settings.web_search.test")
                        },
                    )
                }

                testStatus?.let { status ->
                    Text(
                        text = when (status) {
                            is TestStatus.Success -> stringResource("settings.web_search.test.success")

                            is TestStatus.Failure -> stringResource("settings.web_search.test.fail")
                                .replace("{0}", status.message)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (status) {
                            is TestStatus.Success -> MaterialTheme.colorScheme.primary
                            is TestStatus.Failure -> MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

// ── Conditional field composables ─────────────────────────────────────────────

@Composable
private fun braveApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        AppComponents.appSecretTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource("settings.web_search.api_key")) },
            placeholder = { Text(stringResource("settings.web_search.api_key.placeholder")) },
            modifier = Modifier.fillMaxWidth(),
        )
        linkButton(
            onClick = {
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI("https://brave.com/search/api/"))
                    }
                }
            },
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = stringResource("settings.web_search.get_api_key"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun tavilyApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        AppComponents.appSecretTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource("settings.web_search.api_key")) },
            placeholder = { Text(stringResource("settings.web_search.api_key.placeholder")) },
            modifier = Modifier.fillMaxWidth(),
        )
        linkButton(
            onClick = {
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI("https://app.tavily.com"))
                    }
                }
            },
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = stringResource("settings.web_search.get_api_key"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun searxngEndpointField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource("settings.web_search.endpoint")) },
        placeholder = { Text(stringResource("settings.web_search.endpoint.placeholder")) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

// ── Internal state ─────────────────────────────────────────────────────────────

private sealed interface TestStatus {
    data class Success(val message: String) : TestStatus
    data class Failure(val message: String) : TestStatus
}
