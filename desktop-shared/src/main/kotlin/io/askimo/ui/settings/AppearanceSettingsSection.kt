/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ViewComfy
import androidx.compose.material.icons.filled.ViewCompact
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
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
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.AccountPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.BackgroundImage
import io.askimo.ui.common.theme.FontSize
import io.askimo.ui.common.theme.LayoutDensity
import io.askimo.ui.common.theme.LineSpacing
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemeMode
import io.askimo.ui.common.theme.ThemePaletteStyle
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.theme.applyAccentToColorScheme
import io.askimo.ui.common.theme.detectMacOSDarkMode
import io.askimo.ui.common.theme.loadCodeFontFamily
import io.askimo.ui.common.theme.loadUiFontFamily
import io.askimo.ui.common.theme.parseAccentColor
import io.askimo.ui.common.theme.toAccentHex
import io.askimo.ui.common.ui.asyncImage
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.service.AvatarService
import io.askimo.ui.service.BackgroundImageService
import io.askimo.ui.util.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.swing.JColorChooser
import javax.swing.UIManager
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.skia.Image as SkiaImage

private data class AccentPreset(
    val label: String,
    val hex: String,
    val color: Color,
)

private data class PaletteStyleOption(
    val style: ThemePaletteStyle,
    val titleKey: String,
    val descriptionKey: String,
)

private val paletteStyleOptions = listOf(
    PaletteStyleOption(
        style = ThemePaletteStyle.BALANCED,
        titleKey = "settings.appearance.palette.balanced",
        descriptionKey = "settings.appearance.palette.balanced.description",
    ),
    PaletteStyleOption(
        style = ThemePaletteStyle.VIBRANT,
        titleKey = "settings.appearance.palette.vibrant",
        descriptionKey = "settings.appearance.palette.vibrant.description",
    ),
    PaletteStyleOption(
        style = ThemePaletteStyle.LITERAL,
        titleKey = "settings.appearance.palette.literal",
        descriptionKey = "settings.appearance.palette.literal.description",
    ),
    PaletteStyleOption(
        style = ThemePaletteStyle.SOFT,
        titleKey = "settings.appearance.palette.soft",
        descriptionKey = "settings.appearance.palette.soft.description",
    ),
)

private val accentPresets = listOf(
    AccentPreset(label = "Blue", hex = "#0284C7", color = Color(0xFF0284C7)),
    AccentPreset(label = "Emerald", hex = "#10B981", color = Color(0xFF10B981)),
    AccentPreset(label = "Violet", hex = "#8B5CF6", color = Color(0xFF8B5CF6)),
    AccentPreset(label = "Rose", hex = "#E11D48", color = Color(0xFFE11D48)),
    AccentPreset(label = "Amber", hex = "#F59E0B", color = Color(0xFFF59E0B)),
    // Legacy curated theme primaries (kept as quick accent presets)
    AccentPreset(label = "Sepia", hex = "#6B4226", color = Color(0xFF6B4226)),
    AccentPreset(label = "Ocean", hex = "#0284C7", color = Color(0xFF0284C7)),
    AccentPreset(label = "Nord", hex = "#88C0D0", color = Color(0xFF88C0D0)),
    AccentPreset(label = "Sage", hex = "#4A7C59", color = Color(0xFF4A7C59)),
    AccentPreset(label = "Indigo", hex = "#818CF8", color = Color(0xFF818CF8)),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun appearanceSettingsSection() {
    val currentThemeMode by ThemePreferences.themeMode.collectAsState()
    val currentLayoutDensity by ThemePreferences.layoutDensity.collectAsState()
    val currentBackground by ThemePreferences.backgroundImage.collectAsState()
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
                    text = stringResource("settings.appearance"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = Spacing.small),
                )

                // Language & Font (formerly General settings)
                languageSelectionCard()
                fontSettingsCard()

                // Theme Mode Section
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource("settings.theme"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                // Light Mode
                themeOption(
                    title = stringResource("theme.light"),
                    description = stringResource("theme.light.description"),
                    icon = { Icon(Icons.Default.LightMode, contentDescription = null) },
                    selected = currentThemeMode == ThemeMode.LIGHT,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.LIGHT) },
                )

                // Dark Mode
                themeOption(
                    title = stringResource("theme.dark"),
                    description = stringResource("theme.dark.description"),
                    icon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                    selected = currentThemeMode == ThemeMode.DARK,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.DARK) },
                )

                // System Mode
                themeOption(
                    title = stringResource("theme.system"),
                    description = stringResource("theme.system.description"),
                    icon = { Icon(Icons.Default.Contrast, contentDescription = null) },
                    selected = currentThemeMode == ThemeMode.SYSTEM,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.SYSTEM) },
                )

                // Accent Color Section
                Spacer(modifier = Modifier.height(Spacing.small))
                accentColorSection()

                // Layout Density Section
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource("settings.layout.density"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource("settings.layout.density.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                themeOption(
                    title = stringResource("layout.density.comfortable"),
                    description = stringResource("layout.density.comfortable.description"),
                    icon = { Icon(Icons.Default.ViewComfy, contentDescription = null) },
                    selected = currentLayoutDensity == LayoutDensity.COMFORTABLE,
                    onClick = { ThemePreferences.setLayoutDensity(LayoutDensity.COMFORTABLE) },
                )

                themeOption(
                    title = stringResource("layout.density.compact"),
                    description = stringResource("layout.density.compact.description"),
                    icon = { Icon(Icons.Default.ViewCompact, contentDescription = null) },
                    selected = currentLayoutDensity == LayoutDensity.COMPACT,
                    onClick = { ThemePreferences.setLayoutDensity(LayoutDensity.COMPACT) },
                )

                themeOption(
                    title = stringResource("layout.density.relaxed"),
                    description = stringResource("layout.density.relaxed.description"),
                    icon = { Icon(Icons.Default.ViewComfy, contentDescription = null) },
                    selected = currentLayoutDensity == LayoutDensity.RELAXED,
                    onClick = { ThemePreferences.setLayoutDensity(LayoutDensity.RELAXED) },
                )

                themeOption(
                    title = stringResource("layout.density.spacious"),
                    description = stringResource("layout.density.spacious.description"),
                    icon = { Icon(Icons.Default.ViewComfy, contentDescription = null) },
                    selected = currentLayoutDensity == LayoutDensity.SPACIOUS,
                    onClick = { ThemePreferences.setLayoutDensity(LayoutDensity.SPACIOUS) },
                )

                // Background Image Section
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource("settings.background.image"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource("settings.background.image.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    // "None" tile
                    backgroundImageOption(
                        backgroundImage = BackgroundImage.None,
                        selected = currentBackground is BackgroundImage.None,
                        onClick = {
                            BackgroundImageService.removeCustomBackground()
                            ThemePreferences.setBackgroundImage(BackgroundImage.None)
                        },
                    )
                    // Preset tiles — only those whose image file is actually bundled
                    BackgroundImage.availablePresets.forEach { preset ->
                        backgroundImageOption(
                            backgroundImage = preset,
                            selected = currentBackground == preset,
                            onClick = {
                                BackgroundImageService.removeCustomBackground()
                                ThemePreferences.setBackgroundImage(preset)
                            },
                        )
                    }
                    // Custom image tile — shows current custom selection or a "Browse…" placeholder
                    val customBg = currentBackground as? BackgroundImage.Custom
                    backgroundImageCustomOption(
                        current = customBg,
                        selected = customBg != null,
                        onFilePicked = { path ->
                            val storedPath = BackgroundImageService.saveCustomBackground(path) ?: path
                            ThemePreferences.setBackgroundImage(BackgroundImage.Custom(filePath = storedPath))
                        },
                        onRemove = {
                            BackgroundImageService.removeCustomBackground()
                            ThemePreferences.setBackgroundImage(BackgroundImage.None)
                        },
                    )
                }

                // AI Avatar Sectattion
                aiAvatarSettingsSection()

                // Display Scale Section — macOS manages its own HiDPI scaling; skip on Mac
                if (!Platform.isMac) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    uiScaleSection()
                }

                // Hardware Acceleration Section
                Spacer(modifier = Modifier.height(Spacing.small))
                hardwareAccelerationSection()
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
private fun accentColorSection() {
    val savedAccentHex by ThemePreferences.accentColorHex.collectAsState()
    val selectedPaletteStyle by ThemePreferences.themePaletteStyle.collectAsState()
    val currentThemeMode by ThemePreferences.themeMode.collectAsState()
    var accentInput by remember(savedAccentHex) { mutableStateOf(savedAccentHex ?: "") }
    val parsedAccent = remember(accentInput) { parseAccentColor(accentInput) }
    val normalizedInput = remember(accentInput) { parseAccentColor(accentInput)?.let(::toAccentHex) }
    val pickerTitle = stringResource("settings.appearance.accent.pick.title")
    val scope = rememberCoroutineScope()
    val isDarkPreview = when (currentThemeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> detectMacOSDarkMode()
    }

    // Debounce: apply valid hex 300 ms after the user stops typing.
    // Presets and the color picker bypass this and apply immediately via applyAccent().
    LaunchedEffect(accentInput) {
        if (parsedAccent != null && normalizedInput != savedAccentHex) {
            delay(300.milliseconds)
            ThemePreferences.setAccentColorHex(normalizedInput)
        }
    }

    Text(
        text = stringResource("settings.appearance.accent"),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = stringResource("settings.appearance.accent.description"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = accentInput,
                    onValueChange = { accentInput = it.trim() },
                    modifier = Modifier.widthIn(max = 300.dp),
                    label = { Text(stringResource("settings.appearance.accent.label")) },
                    placeholder = { Text("#0284C7") },
                    singleLine = true,
                    isError = accentInput.isNotBlank() && parsedAccent == null,
                    supportingText = {
                        if (accentInput.isNotBlank() && parsedAccent == null) {
                            Text(
                                text = stringResource("settings.appearance.accent.invalid"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    colors = AppComponents.outlinedTextFieldColors(),
                )

                secondaryButton(
                    onClick = {
                        scope.launch {
                            val selected = pickAccentColor(
                                initial = parsedAccent,
                                title = pickerTitle,
                            )
                            if (selected != null) {
                                val hex = toAccentHex(selected)
                                accentInput = hex
                                ThemePreferences.setAccentColorHex(hex)
                            }
                        }
                    },
                ) {
                    Text(stringResource("settings.appearance.accent.pick"))
                }

                if (savedAccentHex != null) {
                    secondaryButton(
                        onClick = {
                            ThemePreferences.setAccentColorHex(null)
                            accentInput = ""
                        },
                    ) {
                        Text(stringResource("settings.appearance.accent.reset"))
                    }
                }
            }

            Text(
                text = stringResource("settings.appearance.accent.presets"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f),
            )

            // Preset tiles — apply immediately on click
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                accentPresets.forEach { preset ->
                    accentPresetTile(
                        preset = preset,
                        selected = normalizedInput == preset.hex,
                        onClick = {
                            accentInput = preset.hex
                            ThemePreferences.setAccentColorHex(preset.hex)
                        },
                    )
                }
            }

            // Preview swatches — always reflect what would be applied
            val previewScheme = if (parsedAccent != null) {
                applyAccentToColorScheme(
                    accent = parsedAccent,
                    isDark = isDarkPreview,
                    paletteStyle = selectedPaletteStyle,
                )
            } else {
                MaterialTheme.colorScheme
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                accentColorSwatch(previewScheme.primary, stringResource("settings.appearance.accent.swatch.primary"))
                accentColorSwatch(previewScheme.primaryContainer, stringResource("settings.appearance.accent.swatch.primary.container"))
                accentColorSwatch(previewScheme.onPrimaryContainer, stringResource("settings.appearance.accent.swatch.on.primary.container"))
            }
        }
    }

    Text(
        text = stringResource("settings.appearance.palette"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = stringResource("settings.appearance.palette.description"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        paletteStyleOptions.forEach { option ->
            themeOption(
                title = stringResource(option.titleKey),
                description = stringResource(option.descriptionKey),
                icon = { },
                selected = selectedPaletteStyle == option.style,
                onClick = { ThemePreferences.setThemePaletteStyle(option.style) },
            )
        }
    }
}

@Composable
private fun accentColorSwatch(color: Color, label: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color, RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f),
        )
    }
}

@Composable
private fun accentPresetTile(
    preset: AccentPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(92.dp)
            .heightIn(min = 72.dp)
            .pointerHoverIcon(PointerIcon.Hand),
        colors = if (selected) AppComponents.secondaryCardColors() else AppComponents.surfaceVariantCardColors(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(preset.color, CircleShape)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) selectedTextColor else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preset.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) selectedTextColor else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = preset.hex,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        selectedTextColor.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun pickAccentColor(initial: Color?, title: String): Color? {
    val initialAwt = initial?.toAwtColor() ?: UIManager.getColor("ColorChooser.swatchesDefaultRecentColor")
    val picked = JColorChooser.showDialog(
        null,
        title,
        initialAwt,
    ) ?: return null
    return Color(
        red = picked.red / 255f,
        green = picked.green / 255f,
        blue = picked.blue / 255f,
    )
}

private fun Color.toAwtColor(): java.awt.Color {
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)
    return java.awt.Color(r, g, b)
}

@Composable
private fun themeOption(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickableCard(onClick = onClick),
        colors = if (selected) {
            AppComponents.primaryCardColors()
        } else {
            AppComponents.surfaceVariantCardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.large),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                icon()
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun aiAvatarSettingsSection() {
    val avatarService = remember { AvatarService() }
    var aiAvatarPath by remember { mutableStateOf(ThemePreferences.getAIAvatarPath()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        Spacer(modifier = Modifier.height(Spacing.small))

        Text(
            text = stringResource("settings.appearance.avatar.ai"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Text(
            text = stringResource("settings.appearance.avatar.ai.description"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // AI avatar only (user avatar is in User Profile now)
        avatarSetting(
            label = stringResource("settings.appearance.avatar.ai.label"),
            currentAvatar = aiAvatarPath,
            defaultIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            onSelectAvatar = { path ->
                val savedPath = avatarService.saveAIAvatar(path)
                if (savedPath != null) {
                    ThemePreferences.setAIAvatarPath(savedPath)
                    aiAvatarPath = savedPath
                }
            },
            onRemoveAvatar = {
                avatarService.removeAIAvatar()
                ThemePreferences.setAIAvatarPath(null)
                aiAvatarPath = null
            },
        )
    }
}

@Composable
private fun avatarSetting(
    label: String,
    currentAvatar: String?,
    defaultIcon: @Composable () -> Unit,
    onSelectAvatar: (String) -> Unit,
    onRemoveAvatar: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppComponents.surfaceVariantCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                // Avatar preview
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                        )
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (currentAvatar != null) {
                        // Load and display avatar image
                        asyncImage(
                            imagePath = currentAvatar,
                            contentDescription = label,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                        )
                    } else {
                        // Default icon
                        defaultIcon()
                    }
                }

                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                primaryButton(
                    onClick = {
                        scope.launch {
                            val path = FileDialogUtils.pickImagePath("Select Avatar")
                            if (path != null) onSelectAvatar(path)
                        }
                    },
                ) {
                    Text(stringResource("settings.appearance.avatar.select"))
                }

                if (currentAvatar != null) {
                    dangerButton(
                        onClick = onRemoveAvatar,
                    ) {
                        Text(stringResource("action.remove"))
                    }
                }
            }
        }
    }
}

@Composable
private fun backgroundImageOption(
    backgroundImage: BackgroundImage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val painter = remember(backgroundImage) {
        try {
            val bytes: ByteArray? = when (backgroundImage) {
                is BackgroundImage.Preset -> Thread.currentThread().contextClassLoader
                    ?.getResourceAsStream(backgroundImage.resourcePath)?.readBytes()
                    ?: object {}.javaClass.getResourceAsStream("/${backgroundImage.resourcePath}")
                        ?.readBytes()

                is BackgroundImage.Custom -> {
                    val f = File(backgroundImage.filePath)
                    if (f.exists()) f.readBytes() else null
                }

                else -> null
            }
            if (bytes != null) BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()) else null
        } catch (_: Exception) {
            null
        }
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .size(width = 140.dp, height = 90.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = backgroundImage.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Dim overlay so label text is readable
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
                )
            } else {
                // "None" — plain surface tile
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }

            // Label
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Text(
                    text = backgroundImage.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (painter != null) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * A tile that lets the user browse for their own image file.
 * When [current] is non-null the tile shows a preview of the chosen image;
 * clicking it opens the file picker so they can replace it.
 * A small delete button appears in the top-start corner when an image is active
 * so the user can remove it and revert to no background.
 */
@Composable
private fun backgroundImageCustomOption(
    current: BackgroundImage.Custom?,
    selected: Boolean,
    onFilePicked: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val browseLabel = stringResource("settings.background.image.browse")

    val painter = remember(current) {
        if (current == null) return@remember null
        try {
            val f = File(current.filePath)
            if (!f.exists()) return@remember null
            BitmapPainter(SkiaImage.makeFromEncoded(f.readBytes()).toComposeImageBitmap())
        } catch (_: Exception) {
            null
        }
    }

    Card(
        onClick = {
            scope.launch {
                val path = FileDialogUtils.pickImagePath(browseLabel)
                if (path != null) onFilePicked(path)
            }
        },
        modifier = modifier
            .size(width = 140.dp, height = 90.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = browseLabel,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
                )
            } else {
                // No custom image yet — show a dashed/placeholder tile
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = browseLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // Label
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Text(
                    text = if (painter != null) stringResource("settings.background.image.custom") else browseLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (painter != null) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }

                // Delete button — top-start corner
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.error, shape = CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onRemove() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove custom background",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun uiScaleSection() {
    data class ScaleOption(val label: String, val value: Float)

    // Linux (X11/XRender) only honours integer UI-scale values; fractional values are
    // silently rounded to 1 by Java2D, so we only expose options that actually work.
    val scaleOptions = if (Platform.isLinux) {
        listOf(
            ScaleOption("100%", 1.0f),
            ScaleOption("200%", 2.0f),
            ScaleOption("300%", 3.0f),
        )
    } else {
        listOf(
            ScaleOption("75%", 0.75f),
            ScaleOption("100%", 1.0f),
            ScaleOption("125%", 1.25f),
            ScaleOption("150%", 1.5f),
            ScaleOption("175%", 1.75f),
            ScaleOption("200%", 2.0f),
        )
    }

    var currentScale by remember { mutableStateOf(AccountPreferences.device().getUiScale() ?: 1.0f) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showRestartNotice by remember { mutableStateOf(false) }

    val selectedOption = scaleOptions.minByOrNull { kotlin.math.abs(it.value - currentScale) }

    Text(
        text = stringResource("settings.ui.scale.title"),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("settings.ui.scale.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = Spacing.large),
                )

                Box(modifier = Modifier.widthIn(min = 100.dp, max = 160.dp)) {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickableCard { dropdownExpanded = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selectedOption?.label ?: "${currentScale * 100}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change scale",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    AppComponents.dropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        scaleOptions.forEachIndexed { index, option ->
                            AppComponents.themedDropdownMenuItem(
                                text = { Text(text = option.label, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    currentScale = option.value
                                    AccountPreferences.device().setUiScale(option.value)
                                    dropdownExpanded = false
                                    showRestartNotice = true
                                },
                                isSelected = option == selectedOption,
                                showDivider = index < scaleOptions.lastIndex,
                            )
                        }
                    }
                }
            }

            if (showRestartNotice) {
                Text(
                    text = stringResource("settings.ui.scale.restart.notice"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = buildAnnotatedString {
                        withLink(
                            LinkAnnotation.Url(
                                url = "https://askimo.chat/docs/desktop/hidpi/",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) {
                            append(stringResource("settings.ui.scale.learn.more"))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun hardwareAccelerationSection() {
    var isEnabled by remember { mutableStateOf(AccountPreferences.device().getHardwareAccelerationEnabled()) }
    var showRestartNotice by remember { mutableStateOf(false) }

    Text(
        text = stringResource("settings.hardware.acceleration.title"),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (Platform.isWindows) {
                        stringResource("settings.hardware.acceleration.description.windows")
                    } else {
                        stringResource("settings.hardware.acceleration.description")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        isEnabled = checked
                        AccountPreferences.device().setHardwareAccelerationEnabled(checked)
                        showRestartNotice = true
                    },
                )
            }

            if (showRestartNotice) {
                Text(
                    text = stringResource("settings.hardware.acceleration.restart.notice"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
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
                themedTooltip(text = stringResource("settings.ai.response.language.tooltip")) {
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
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
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
                    AppComponents.themedDropdownMenuItem(
                        text = { Text(stringResource("settings.ai.response.language.auto"), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            AppConfig.updateField("chat.defaultResponseAILocale", "")
                            aiLanguageDropdownExpanded = false
                        },
                        isSelected = currentAILocale == null,
                        showDivider = true,
                    )
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = AppComponents.bannerCardColors(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            fontFamilySelector(
                label = stringResource("settings.font.family.ui"),
                selectedFontFamily = currentFontSettings.uiFontFamily,
                availableFonts = availableFonts,
                previewResolver = ::loadUiFontFamily,
                onSelected = { ThemePreferences.setFontSettings(currentFontSettings.copy(uiFontFamily = it)) },
            )
            HorizontalDivider()
            fontFamilySelector(
                label = stringResource("settings.font.family.code"),
                selectedFontFamily = currentFontSettings.codeFontFamily,
                availableFonts = availableFonts,
                previewResolver = ::loadCodeFontFamily,
                onSelected = { ThemePreferences.setFontSettings(currentFontSettings.copy(codeFontFamily = it)) },
            )
            HorizontalDivider()
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
                        modifier = Modifier.fillMaxWidth().clickableCard { fontSizeDropdownExpanded = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(currentFontSettings.fontSize.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Default.Edit, contentDescription = "Change size", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    AppComponents.dropdownMenu(expanded = fontSizeDropdownExpanded, onDismissRequest = { fontSizeDropdownExpanded = false }) {
                        FontSize.entries.forEachIndexed { index, fontSize ->
                            AppComponents.themedDropdownMenuItem(
                                text = { Text(fontSize.displayName, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    ThemePreferences.setFontSettings(currentFontSettings.copy(fontSize = fontSize))
                                    fontSizeDropdownExpanded = false
                                },
                                isSelected = fontSize == currentFontSettings.fontSize,
                                showDivider = index < FontSize.entries.lastIndex,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
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
                        modifier = Modifier.fillMaxWidth().clickableCard { lineSpacingDropdownExpanded = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(currentFontSettings.lineSpacing.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Default.Edit, contentDescription = "Change line spacing", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    AppComponents.dropdownMenu(expanded = lineSpacingDropdownExpanded, onDismissRequest = { lineSpacingDropdownExpanded = false }) {
                        LineSpacing.entries.forEachIndexed { index, spacing ->
                            AppComponents.themedDropdownMenuItem(
                                text = { Text(spacing.displayName, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    ThemePreferences.setFontSettings(currentFontSettings.copy(lineSpacing = spacing))
                                    lineSpacingDropdownExpanded = false
                                },
                                isSelected = spacing == currentFontSettings.lineSpacing,
                                showDivider = index < LineSpacing.entries.lastIndex,
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
                modifier = Modifier.fillMaxWidth().clickableCard { dropdownExpanded = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(selectedFontFamily, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Icon(Icons.Default.Edit, contentDescription = "Change font", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            AppComponents.dropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                availableFonts.forEachIndexed { index, fontFamily ->
                    AppComponents.themedDropdownMenuItem(
                        text = {
                            Text(
                                text = fontFamily,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = previewResolver(fontFamily)),
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
