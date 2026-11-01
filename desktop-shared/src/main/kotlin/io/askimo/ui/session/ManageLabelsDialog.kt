/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.askimo.core.chat.domain.LABEL_NAME_MAX_LENGTH
import io.askimo.core.chat.domain.Label
import io.askimo.core.i18n.LocalizationManager
import io.askimo.ui.common.components.dangerButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppColors
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppTextStyles
import io.askimo.ui.common.theme.Spacing

/** Material3's default `OutlinedTextField` input-box height, used to vertically center the
 * adjacent "Add" button against just the box itself (not the taller supportingText row below). */
private val TEXT_FIELD_BOX_HEIGHT = 56.dp

/**
 * Dialog for creating, renaming/recoloring, and deleting label definitions
 * (`io.askimo.core.chat.domain.Label`), mirroring [manageDirectivesDialog]'s structure.
 *
 * Label *assignment* to sessions happens elsewhere (the sidebar's per-session "Labels" menu,
 * see [labelsMenuItem]) — this dialog only manages the label catalog itself.
 *
 * @param usageCounts labelId → number of sessions currently carrying that label, used to warn
 *   before a destructive delete (see [labelDeleteConfirmDialog]).
 */
@Composable
fun manageLabelsDialog(
    labels: List<Label>,
    usageCounts: Map<String, Int> = emptyMap(),
    onCreate: (name: String, color: String) -> Unit,
    onUpdate: (Label) -> Unit,
    onDelete: (labelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newLabelName by remember { mutableStateOf("") }
    var newLabelColor by remember { mutableStateOf(LABEL_COLOR_PALETTE.first()) }
    var newLabelError by remember { mutableStateOf<String?>(null) }

    fun submitNewLabel() {
        val trimmed = newLabelName.trim()
        if (trimmed.isEmpty()) return
        if (labels.any { it.name.equals(trimmed, ignoreCase = true) }) {
            newLabelError = LocalizationManager.getString("label.error.duplicate", trimmed)
            return
        }
        onCreate(trimmed, newLabelColor)
        newLabelName = ""
        newLabelError = null
    }

    AppComponents.scaffoldDialogLazyColumn(
        onDismissRequest = onDismiss,
        onCloseRequest = onDismiss,
        width = 560.dp,
        modifier = Modifier.heightIn(min = 420.dp, max = 600.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                text = stringResource("label.manage.title"),
                style = AppTextStyles.pageTitle,
            )
        },
        stickyHeader = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = stringResource("label.manage.new.title"),
                    style = AppTextStyles.sectionTitle,
                )
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = newLabelName,
                        onValueChange = {
                            if (it.length <= LABEL_NAME_MAX_LENGTH) newLabelName = it
                            newLabelError = null
                        },
                        placeholder = { Text(stringResource("label.manage.new.placeholder")) },
                        singleLine = true,
                        isError = newLabelError != null,
                        supportingText = {
                            Text(
                                text = newLabelError ?: "${newLabelName.length}/$LABEL_NAME_MAX_LENGTH",
                                color = if (newLabelError != null) MaterialTheme.colorScheme.error else AppTextStyles.secondaryContent,
                                style = AppTextStyles.hint,
                            )
                        },
                        colors = AppColors.outlinedTextFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitNewLabel() }),
                        modifier = Modifier.weight(1f),
                    )
                    // Matches OutlinedTextField's default input-box height so the button centers
                    // against just that box, ignoring the extra height the supportingText row adds.
                    Box(
                        modifier = Modifier.height(TEXT_FIELD_BOX_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        primaryButton(
                            onClick = { submitNewLabel() },
                            enabled = newLabelName.trim().isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource("action.add"))
                        }
                    }
                }
                colorSwatchRow(
                    selectedColor = newLabelColor,
                    onSelectColor = { newLabelColor = it },
                )
                hexColorField(
                    selectedColor = newLabelColor,
                    onSelectColor = { newLabelColor = it },
                )
            }
        },
        content = {
            if (labels.isEmpty()) {
                item {
                    Text(
                        text = stringResource("label.manage.empty"),
                        style = AppTextStyles.bodySecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.extraLarge),
                    )
                }
            } else {
                items(labels, key = { it.id }) { label ->
                    labelChip(
                        label = label,
                        otherLabelNames = labels.filter { it.id != label.id }.map { it.name },
                        usageCount = usageCounts[label.id] ?: 0,
                        onUpdate = onUpdate,
                        onDelete = { onDelete(label.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        actions = {
            Spacer(modifier = Modifier.weight(1f))
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.close"))
            }
        },
    )
}

/**
 * A compact, flat label row (dot + name + conversation count + edit affordance) with a
 * trailing edit icon that opens an anchored, non-modal popover — see
 * [labelChipEditorPopover] — instead of stacking a second dialog on top of
 * [manageLabelsDialog]. Deletion is still behind a confirmation dialog (see
 * [labelDeleteConfirmDialog]) since that's a destructive, one-shot action rather than an
 * editing flow.
 */
@Composable
private fun labelChip(
    label: Label,
    otherLabelNames: List<String>,
    usageCount: Int,
    onUpdate: (Label) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEditor by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        labelDeleteConfirmDialog(
            labelName = label.name,
            usageCount = usageCount,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, AppColors.codeBlockBorderColor(), RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .clickable { showEditor = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = Spacing.small, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(parseLabelColor(label.color)),
            )
            Text(
                text = label.name,
                style = AppTextStyles.itemTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = AppTextStyles.secondaryContent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "$usageCount",
                style = AppTextStyles.hint,
                color = AppTextStyles.secondaryContent,
            )
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource("action.edit"),
                tint = AppTextStyles.secondaryContent,
                modifier = Modifier.size(16.dp),
            )
        }

        if (showEditor) {
            labelChipEditorPopover(
                label = label,
                otherLabelNames = otherLabelNames,
                onUpdate = onUpdate,
                onDeleteRequest = {
                    showEditor = false
                    showDeleteConfirm = true
                },
                onDismiss = { showEditor = false },
            )
        }
    }
}

/**
 * Anchored, non-modal popover (an [AppComponents.dropdownMenu]) for editing a single label's
 * name/color. Opened from [labelChip]'s edit icon; outside clicks / Escape discard unsaved
 * edits, Enter or the "Save" button commits them. This avoids stacking a second modal dialog
 * on top of [manageLabelsDialog] just to edit one label.
 */
@Composable
private fun labelChipEditorPopover(
    label: Label,
    otherLabelNames: List<String>,
    onUpdate: (Label) -> Unit,
    onDeleteRequest: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(label.id) { mutableStateOf(label.name) }
    var color by remember(label.id) { mutableStateOf(label.color) }
    var nameError by remember { mutableStateOf<String?>(null) }

    fun submitAndClose() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (otherLabelNames.any { it.equals(trimmed, ignoreCase = true) }) {
            nameError = LocalizationManager.getString("label.error.duplicate", trimmed)
            return
        }
        onUpdate(label.copy(name = trimmed, color = color))
        onDismiss()
    }

    AppComponents.dropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource("label.edit.title"),
                style = AppTextStyles.sectionTitle,
            )
            OutlinedTextField(
                value = name,
                onValueChange = {
                    if (it.length <= LABEL_NAME_MAX_LENGTH) name = it
                    nameError = null
                },
                singleLine = true,
                isError = nameError != null,
                supportingText = {
                    Text(
                        text = nameError ?: "${name.length}/$LABEL_NAME_MAX_LENGTH",
                        color = if (nameError != null) MaterialTheme.colorScheme.error else AppTextStyles.secondaryContent,
                        style = AppTextStyles.hint,
                    )
                },
                colors = AppColors.outlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitAndClose() }),
                modifier = Modifier.fillMaxWidth(),
            )
            colorSwatchRow(
                selectedColor = color,
                onSelectColor = { color = it },
            )
            hexColorField(
                selectedColor = color,
                onSelectColor = { color = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDeleteRequest,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource("action.delete"), tint = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.weight(1f))
                secondaryButton(onClick = onDismiss) {
                    Text(stringResource("action.cancel"))
                }
                Spacer(modifier = Modifier.width(Spacing.small))
                primaryButton(
                    onClick = { submitAndClose() },
                    enabled = name.trim().isNotEmpty(),
                ) {
                    Text(stringResource("action.save"))
                }
            }
        }
    }
}

/** Wrapping grid of clickable color swatches from [LABEL_COLOR_PALETTE], highlighting
 *  [selectedColor] — uses [FlowRow] so all presets stay visible regardless of container width. */
@Composable
private fun colorSwatchRow(selectedColor: String, onSelectColor: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        modifier = Modifier.selectableGroup(),
    ) {
        LABEL_COLOR_PALETTE.forEach { hex ->
            val isSelected = hex.equals(selectedColor, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(parseLabelColor(hex))
                    .let {
                        if (isSelected) {
                            it.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            it
                        }
                    }
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelectColor(hex) },
                    )
                    .semantics {
                        contentDescription = hex
                        selected = isSelected
                    }
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
}

private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

/**
 * Custom hex-code entry for label color, complementing [colorSwatchRow]'s curated palette.
 * Keeps its own text buffer so typing isn't disrupted by the round-trip through [selectedColor]
 * — it only resyncs from [selectedColor] when the change originated elsewhere (e.g. a swatch
 * click), tracked via [lastPushed], not when this field's own [onSelectColor] call caused it.
 */
@Composable
private fun hexColorField(selectedColor: String, onSelectColor: (String) -> Unit) {
    var hexText by remember { mutableStateOf(selectedColor) }
    var lastPushed by remember { mutableStateOf(selectedColor) }
    var hexError by remember { mutableStateOf(false) }

    LaunchedEffect(selectedColor) {
        if (!selectedColor.equals(lastPushed, ignoreCase = true)) {
            hexText = selectedColor
            lastPushed = selectedColor
            hexError = false
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(parseLabelColor(selectedColor))
                .border(1.dp, AppColors.codeBlockBorderColor(), CircleShape),
        )
        OutlinedTextField(
            value = hexText,
            onValueChange = { raw ->
                hexText = raw
                val candidate = if (raw.startsWith("#")) raw else "#$raw"
                if (HEX_COLOR_REGEX.matches(candidate)) {
                    hexError = false
                    lastPushed = candidate
                    onSelectColor(candidate)
                } else {
                    hexError = raw.isNotEmpty()
                }
            },
            placeholder = { Text(stringResource("label.color.hex.placeholder")) },
            singleLine = true,
            isError = hexError,
            supportingText = if (hexError) {
                { Text(stringResource("label.color.hex.error"), color = MaterialTheme.colorScheme.error, style = AppTextStyles.hint) }
            } else {
                null
            },
            colors = AppColors.outlinedTextFieldColors(),
            modifier = Modifier.width(160.dp),
        )
    }
}

/**
 * Confirmation dialog shown before deleting a label definition — warns when [usageCount] > 0
 * since deleting cascades the label off every session still carrying it.
 */
@Composable
private fun labelDeleteConfirmDialog(
    labelName: String,
    usageCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppComponents.alertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = stringResource("label.delete.confirm.title"),
                    style = AppTextStyles.pageTitle,
                )
            }
        },
        text = {
            Text(
                text = if (usageCount > 0) {
                    stringResource("label.delete.confirm.message.used", labelName, "$usageCount")
                } else {
                    stringResource("label.delete.confirm.message.unused", labelName)
                },
                style = AppTextStyles.body,
            )
        },
        dismissButton = {
            secondaryButton(onClick = onDismiss) {
                Text(stringResource("action.cancel"))
            }
        },
        confirmButton = {
            dangerButton(onClick = onConfirm) {
                Text(stringResource("label.delete.confirm.button"))
            }
        },
    )
}
