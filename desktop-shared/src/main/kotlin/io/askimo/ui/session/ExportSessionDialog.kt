/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.export.ExportFormat
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.ui.util.FileDialogUtils
import java.io.File

/**
 * Combined export session dialog that allows users to select export format and file location.
 *
 * @param sessionTitle The title of the session being exported
 * @param selectedFormat Currently selected export format
 * @param defaultFilename Default filename with extension
 * @param onFormatChange Callback when format is changed
 * @param onDismiss Callback when dialog is dismissed
 * @param onExport Callback when export is confirmed with the selected file path
 */
@Composable
fun exportSessionDialog(
    sessionTitle: String,
    selectedFormat: ExportFormat,
    defaultFilename: String,
    onFormatChange: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
    onExport: (String) -> Unit,
) {
    var filePath by remember { mutableStateOf("") }
    var showFileBrowser by remember { mutableStateOf(false) }

    val fileChooserTitle = stringResource("session.export.file.chooser.title")

    // Update file path when format changes
    LaunchedEffect(defaultFilename) {
        if (filePath.isEmpty() || filePath.substringAfterLast('/').substringBeforeLast('.') ==
            defaultFilename.substringBeforeLast('.')
        ) {
            val homeDir = System.getProperty("user.home")
            filePath = "$homeDir/$defaultFilename"
        } else {
            // Update extension only
            val directory = filePath.substringBeforeLast('/')
            val baseFilename = filePath.substringAfterLast('/').substringBeforeLast('.')
            filePath = "$directory/$baseFilename.${selectedFormat.extension}"
        }
    }

    // Native file chooser via FileKit
    if (showFileBrowser) {
        LaunchedEffect(Unit) {
            val baseName = File(filePath).nameWithoutExtension
            val target = FileDialogUtils.pickSavePath(
                suggestedName = baseName,
                extension = selectedFormat.extension,
                title = fileChooserTitle,
            )
            if (target != null) {
                filePath = target.absolutePath
            }
            showFileBrowser = false
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource("session.export.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = sessionTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Description
                Text(
                    text = stringResource("session.export.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Format selection title
                Text(
                    text = stringResource("session.export.select.format"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Markdown format card
                formatCard(
                    format = ExportFormat.MARKDOWN,
                    isSelected = selectedFormat == ExportFormat.MARKDOWN,
                    icon = Icons.Default.Description,
                    onClick = { onFormatChange(ExportFormat.MARKDOWN) },
                )

                // JSON format card
                formatCard(
                    format = ExportFormat.JSON,
                    isSelected = selectedFormat == ExportFormat.JSON,
                    icon = Icons.Default.Code,
                    onClick = { onFormatChange(ExportFormat.JSON) },
                )

                // HTML format card
                formatCard(
                    format = ExportFormat.HTML,
                    isSelected = selectedFormat == ExportFormat.HTML,
                    icon = Icons.Default.Language,
                    onClick = { onFormatChange(ExportFormat.HTML) },
                )

                Spacer(modifier = Modifier.height(4.dp))

                // File path selection
                Text(
                    text = stringResource("session.export.location"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = filePath,
                        onValueChange = { filePath = it },
                        label = { Text(stringResource("session.export.file.path")) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = AppComponents.outlinedTextFieldColors(),
                    )

                    IconButton(
                        onClick = { showFileBrowser = true },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = stringResource("session.export.browse"),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(
                        onClick = onDismiss,
                    ) {
                        Text(stringResource("action.cancel"))
                    }

                    primaryButton(
                        onClick = {
                            if (filePath.isNotBlank()) {
                                onExport(filePath)
                            }
                        },
                        enabled = filePath.isNotBlank(),
                    ) {
                        Text(stringResource("session.export.button.export"))
                    }
                }
            }
        }
    }
}

/**
 * Format selection card showing format details with benefits as tooltip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun formatCard(
    format: ExportFormat,
    isSelected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    format.getBenefits().forEach { benefit ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "• ")
                            Text(text = benefit)
                        }
                    }
                }
            }
        },
        state = rememberTooltipState(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(32.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Format name and extension
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = format.getDisplayName(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ".${format.extension}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                // Description
                Text(
                    text = format.getDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // Selected indicator
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
