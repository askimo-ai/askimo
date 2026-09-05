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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.export.ExportFormat
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing
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

    AppComponents.scaffoldDialog(
        onDismissRequest = onDismiss,
        width = 700.dp,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                Text(
                    text = stringResource("session.export.title"),
                    style = AppTextStyles.pageTitle,
                )
                Text(
                    text = sessionTitle,
                    style = AppTextStyles.bodySecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        actions = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.cancel"))
            }
            Spacer(modifier = Modifier.width(Spacing.small))
            primaryButton(
                onClick = { if (filePath.isNotBlank()) onExport(filePath) },
                enabled = filePath.isNotBlank(),
            ) {
                Text(stringResource("session.export.button.export"))
            }
        },
    ) {
        // Description
        Text(
            text = stringResource("session.export.description"),
            style = AppTextStyles.bodySecondary,
        )

        // Format selection title
        Text(
            text = stringResource("session.export.select.format"),
            style = AppTextStyles.sectionTitle,
        )

        // Format cards
        formatCard(
            format = ExportFormat.MARKDOWN,
            isSelected = selectedFormat == ExportFormat.MARKDOWN,
            icon = Icons.Default.Description,
            onClick = { onFormatChange(ExportFormat.MARKDOWN) },
        )
        formatCard(
            format = ExportFormat.JSON,
            isSelected = selectedFormat == ExportFormat.JSON,
            icon = Icons.Default.Code,
            onClick = { onFormatChange(ExportFormat.JSON) },
        )
        formatCard(
            format = ExportFormat.HTML,
            isSelected = selectedFormat == ExportFormat.HTML,
            icon = Icons.Default.Language,
            onClick = { onFormatChange(ExportFormat.HTML) },
        )

        // File path section
        Text(
            text = stringResource("session.export.location"),
            style = AppTextStyles.sectionTitle,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filePath,
                onValueChange = { filePath = it },
                label = { Text(stringResource("session.export.file.path")) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = AppColors.outlinedTextFieldColors(),
            )
            IconButton(
                onClick = { showFileBrowser = true },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = stringResource("session.export.browse"),
                    tint = AppTextStyles.primaryContent,
                )
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
        AppColors.codeBlockBorderColor()
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
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
                .padding(Spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(32.dp),
            )

            Spacer(modifier = Modifier.width(Spacing.large))

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                // Format name and extension
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = format.getDisplayName(),
                        style = AppTextStyles.sectionTitle,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.Unspecified,
                    )
                    Spacer(modifier = Modifier.width(Spacing.small))
                    Text(
                        text = ".${format.extension}",
                        style = AppTextStyles.hint,
                        color = if (isSelected) AppColors.contentColorFor(AppColors.Elevation.SELECTED) else Color.Unspecified,
                    )
                }

                // Description
                Text(
                    text = format.getDescription(),
                    style = AppTextStyles.caption,
                    color = if (isSelected) AppColors.contentColorFor(AppColors.Elevation.SELECTED) else Color.Unspecified,
                )
            }

            // Selected indicator
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
