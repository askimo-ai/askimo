/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.core.config.AppConfig
import io.askimo.core.i18n.LocalizationManager
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.FontSettings
import io.askimo.ui.common.theme.FontSize
import io.askimo.ui.common.theme.LineSpacing
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.theme.loadCodeFontFamily
import io.askimo.ui.common.theme.loadUiFontFamily
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun generalSettingsSection() {
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
                    .padding(start = Spacing.extraLarge, top = Spacing.extraLarge, bottom = Spacing.extraLarge, end = 36.dp), // end room for scrollbar
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Text(
                    text = stringResource("settings.general"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                // Language Selection
                languageSelectionCard()

                // Font Settings
                fontSettingsCard()
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
private fun languageSelectionCard() {
    val currentLocale by ThemePreferences.locale.collectAsState()
    var languageDropdownExpanded by remember { mutableStateOf(false) }
    val availableLanguages = remember { LocalizationManager.availableLocales }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppComponents.bannerCardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.app.language"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).padding(end = Spacing.large),
                )
                Box(modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableCard { languageDropdownExpanded = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = availableLanguages[currentLocale] ?: currentLocale.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change language",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = languageDropdownExpanded,
                        onDismissRequest = { languageDropdownExpanded = false },
                    ) {
                        val languages = availableLanguages.entries.toList()
                        languages.forEachIndexed { index, (locale, name) ->
                            AppComponents.themedDropdownMenuItem(
                                text = { Text(text = name, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    ThemePreferences.setLocale(locale)
                                    languageDropdownExpanded = false
                                },
                                isSelected = locale == currentLocale,
                                showDivider = index < languages.lastIndex,
                            )
                        }
                    }
                }
            }

            val crowdinUrl = "https://$DOMAIN/docs/contributing/contributing-localization/"
            val annotatedString = buildAnnotatedString {
                append(stringResource("settings.app.language.translation.help") + " ")
                withLink(
                    LinkAnnotation.Url(
                        url = crowdinUrl,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) {
                    append(stringResource("settings.app.language.translation.contribute"))
                }
            }
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                ),
            )

            HorizontalDivider()

            // Preferred AI Response Language
            preferredAIResponseLanguageField(availableLanguages)
        }
    }
}

@Composable
private fun preferredAIResponseLanguageField(availableLanguages: Map<Locale, String>) {
    var aiLanguageDropdownExpanded by remember { mutableStateOf(false) }
    val currentAILocale = AppConfig.chat.defaultResponseAILocale

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = Spacing.large),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource("settings.ai.response.language"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                themedTooltip(
                    text = stringResource("settings.ai.response.language.tooltip"),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Information",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.width(24.dp),
                    )
                }
            }
            Box(modifier = Modifier.widthIn(min = 160.dp, max = 280.dp)) {
                val aiLangDisplayText = if (currentAILocale == null) {
                    stringResource("settings.ai.response.language.auto")
                } else {
                    availableLanguages.entries.find { it.key.toString() == currentAILocale }?.value
                        ?: currentAILocale
                }
                themedTooltip(text = aiLangDisplayText) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableCard { aiLanguageDropdownExpanded = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = aiLangDisplayText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = Spacing.small),
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change AI response language",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                AppComponents.dropdownMenu(
                    expanded = aiLanguageDropdownExpanded,
                    onDismissRequest = { aiLanguageDropdownExpanded = false },
                ) {
                    // Auto-detect option
                    AppComponents.themedDropdownMenuItem(
                        text = { Text(stringResource("settings.ai.response.language.auto"), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            AppConfig.updateField("chat.defaultResponseAILocale", "")
                            aiLanguageDropdownExpanded = false
                        },
                        isSelected = currentAILocale == null,
                        showDivider = true,
                    )
                    // Language options
                    val langs = availableLanguages.entries.toList()
                    langs.forEachIndexed { index, (locale, name) ->
                        AppComponents.themedDropdownMenuItem(
                            text = { Text(name, style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                AppConfig.updateField("chat.defaultResponseAILocale", locale.toString())
                                aiLanguageDropdownExpanded = false
                            },
                            isSelected = locale.toString() == currentAILocale,
                            showDivider = index < langs.lastIndex,
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource("settings.ai.response.language.description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun fontSettingsCard() {
    val currentFontSettings by ThemePreferences.fontSettings.collectAsState()
    val availableFonts = remember { ThemePreferences.getAvailableSystemFonts() }
    var fontSizeDropdownExpanded by remember { mutableStateOf(false) }
    var lineSpacingDropdownExpanded by remember { mutableStateOf(false) }
    val customScaleEnabled = currentFontSettings.customFontScale != null

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
            fontFamilySelector(
                label = stringResource("settings.font.family.ui"),
                selectedFontFamily = currentFontSettings.uiFontFamily,
                availableFonts = availableFonts,
                previewResolver = ::loadUiFontFamily,
                onSelected = { selected ->
                    ThemePreferences.setFontSettings(currentFontSettings.copy(uiFontFamily = selected))
                },
            )

            HorizontalDivider()

            fontFamilySelector(
                label = stringResource("settings.font.family.code"),
                selectedFontFamily = currentFontSettings.codeFontFamily,
                availableFonts = availableFonts,
                previewResolver = ::loadCodeFontFamily,
                onSelected = { selected ->
                    ThemePreferences.setFontSettings(currentFontSettings.copy(codeFontFamily = selected))
                },
            )

            HorizontalDivider()

            // Font Size — label left, dropdown right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.font.size"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).padding(end = Spacing.large),
                )
                Box(modifier = Modifier.widthIn(min = 120.dp, max = 200.dp)) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableCard { fontSizeDropdownExpanded = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentFontSettings.fontSize.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change size",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = fontSizeDropdownExpanded,
                        onDismissRequest = { fontSizeDropdownExpanded = false },
                    ) {
                        val sizes = FontSize.entries
                        sizes.forEachIndexed { index, fontSize ->
                            AppComponents.themedDropdownMenuItem(
                                text = { Text(text = fontSize.displayName, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    ThemePreferences.setFontSettings(currentFontSettings.copy(fontSize = fontSize))
                                    fontSizeDropdownExpanded = false
                                },
                                isSelected = fontSize == currentFontSettings.fontSize,
                                showDivider = index < sizes.lastIndex,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource("settings.font.custom.scale"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource("settings.font.custom.scale.description"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
                Switch(
                    checked = customScaleEnabled,
                    onCheckedChange = { enabled ->
                        ThemePreferences.setFontSettings(
                            currentFontSettings.copy(
                                customFontScale = if (enabled) {
                                    currentFontSettings.effectiveScale
                                } else {
                                    null
                                },
                            ),
                        )
                    },
                )
            }

            if (customScaleEnabled) {
                val minScale = FontSettings.MIN_CUSTOM_FONT_SCALE
                val maxScale = FontSettings.MAX_CUSTOM_FONT_SCALE
                val currentScale = currentFontSettings.effectiveScale.coerceIn(minScale, maxScale)

                Slider(
                    value = currentScale,
                    onValueChange = { value ->
                        val rounded = ((value * 20f).roundToInt() / 20f).coerceIn(minScale, maxScale)
                        ThemePreferences.setFontSettings(
                            currentFontSettings.copy(customFontScale = rounded),
                        )
                    },
                    valueRange = minScale..maxScale,
                    steps = 33,
                )
                Text(
                    text = stringResource("settings.font.custom.scale.value", (currentScale * 100).roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f),
                )
            }

            HorizontalDivider()

            // Line Spacing — label left, dropdown right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.font.line.spacing"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).padding(end = Spacing.large),
                )
                Box(modifier = Modifier.widthIn(min = 120.dp, max = 200.dp)) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableCard { lineSpacingDropdownExpanded = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentFontSettings.lineSpacing.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change line spacing",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = lineSpacingDropdownExpanded,
                        onDismissRequest = { lineSpacingDropdownExpanded = false },
                    ) {
                        val spacings = LineSpacing.entries
                        spacings.forEachIndexed { index, spacing ->
                            AppComponents.themedDropdownMenuItem(
                                text = { Text(text = spacing.displayName, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    ThemePreferences.setFontSettings(currentFontSettings.copy(lineSpacing = spacing))
                                    lineSpacingDropdownExpanded = false
                                },
                                isSelected = spacing == currentFontSettings.lineSpacing,
                                showDivider = index < spacings.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun fontFamilySelector(
    label: String,
    selectedFontFamily: String,
    availableFonts: List<String>,
    previewResolver: (String) -> FontFamily,
    onSelected: (String) -> Unit,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f).padding(end = Spacing.large),
        )
        Box(modifier = Modifier.widthIn(min = 160.dp, max = 260.dp)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableCard { dropdownExpanded = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedFontFamily,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Change font",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            AppComponents.dropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
            ) {
                availableFonts.forEachIndexed { index, fontFamily ->
                    AppComponents.themedDropdownMenuItem(
                        text = {
                            Text(
                                text = fontFamily,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = previewResolver(fontFamily),
                                ),
                            )
                        },
                        onClick = {
                            onSelected(fontFamily)
                            dropdownExpanded = false
                        },
                        isSelected = fontFamily == selectedFontFamily,
                        showDivider = index < availableFonts.lastIndex,
                    )
                }
            }
        }
    }
}
