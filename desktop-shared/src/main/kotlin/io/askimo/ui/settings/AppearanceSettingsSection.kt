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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ViewComfy
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.BackgroundImage
import io.askimo.ui.common.theme.LayoutDensity
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemeMode
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.asyncImage
import io.askimo.ui.common.ui.clickableCard
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.service.AvatarService
import io.askimo.ui.service.BackgroundImageService
import kotlinx.coroutines.launch
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

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

                // Theme Mode Section
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

                // Sepia Mode
                themeOption(
                    title = stringResource("theme.sepia"),
                    description = stringResource("theme.sepia.description"),
                    icon = {
                        Icon(
                            Icons.Default.LightMode,
                            contentDescription = null,
                            tint = Color(0xFF6B4226),
                        )
                    },
                    selected = currentThemeMode == ThemeMode.SEPIA,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.SEPIA) },
                )

                // Ocean Mode
                themeOption(
                    title = stringResource("theme.ocean"),
                    description = stringResource("theme.ocean.description"),
                    icon = {
                        Icon(
                            Icons.Default.LightMode,
                            contentDescription = null,
                            tint = Color(0xFF0284C7),
                        )
                    },
                    selected = currentThemeMode == ThemeMode.OCEAN,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.OCEAN) },
                )

                // Nord Mode
                themeOption(
                    title = stringResource("theme.nord"),
                    description = stringResource("theme.nord.description"),
                    icon = {
                        Icon(
                            Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = Color(0xFF88C0D0),
                        )
                    },
                    selected = currentThemeMode == ThemeMode.NORD,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.NORD) },
                )

                // Sage Mode
                themeOption(
                    title = stringResource("theme.sage"),
                    description = stringResource("theme.sage.description"),
                    icon = {
                        Icon(
                            Icons.Default.LightMode,
                            contentDescription = null,
                            tint = Color(0xFF4A7C59),
                        )
                    },
                    selected = currentThemeMode == ThemeMode.SAGE,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.SAGE) },
                )

                // Rose Mode
                themeOption(
                    title = stringResource("theme.rose"),
                    description = stringResource("theme.rose.description"),
                    icon = {
                        Icon(
                            Icons.Default.LightMode,
                            contentDescription = null,
                            tint = Color(0xFFE11D48),
                        )
                    },
                    selected = currentThemeMode == ThemeMode.ROSE,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.ROSE) },
                )

                // Indigo Mode
                themeOption(
                    title = stringResource("theme.indigo"),
                    description = stringResource("theme.indigo.description"),
                    icon = {
                        Icon(
                            Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = Color(0xFF818CF8),
                        )
                    },
                    selected = currentThemeMode == ThemeMode.INDIGO,
                    onClick = { ThemePreferences.setThemeMode(ThemeMode.INDIGO) },
                )

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

                // AI Avatar Section
                aiAvatarSettingsSection()
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
