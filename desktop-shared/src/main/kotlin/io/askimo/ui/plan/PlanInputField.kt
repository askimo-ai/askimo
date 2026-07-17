/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ReasoningEffortChangedEvent
import io.askimo.core.event.internal.ThinkingSupportDetectedEvent
import io.askimo.core.providers.ModelCapabilitiesCache
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ReasoningEffort
import io.askimo.ui.common.components.actionInputField
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.AppComponents.dropdownMenu
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.ui.themedTooltip

/**
 * Plan-specific input field.
 *
 * Keeps the reusable send/input behavior, but also exposes the reasoning dropdown
 * when the active provider/model supports thinking.
 */
@Composable
fun planInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isLoading: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 5,
    error: String? = null,
    sendContentDescription: String = "Send",
) {
    val tfv = TextFieldValue(text = value, selection = TextRange(value.length))
    planInputField(
        value = tfv,
        onValueChange = { onValueChange(it.text) },
        onSend = onSend,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        isLoading = isLoading,
        minLines = minLines,
        maxLines = maxLines,
        error = error,
        sendContentDescription = sendContentDescription,
    )
}

/**
 * Overload that works with [TextFieldValue] directly, giving the caller cursor control.
 */
@Composable
fun planInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isLoading: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 5,
    error: String? = null,
    sendContentDescription: String = "Send",
) {
    val appParams = AppContext.getInstance().params
    val resolvedProvider: ModelProvider? = appParams.activeProviderType.takeIf { it != ModelProvider.UNKNOWN }
    val currentModel: String = appParams.model

    var supportsReasoning by remember(resolvedProvider, currentModel) {
        mutableStateOf(
            resolvedProvider != null && currentModel.isNotBlank() &&
                ModelCapabilitiesCache.supportsThinking(resolvedProvider, currentModel),
        )
    }
    var reasoningEffort by remember { mutableStateOf(ReasoningEffort.DEFAULT) }
    var showReasoningDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(resolvedProvider, currentModel) {
        EventBus.internalEvents.collect { event ->
            if (event is ThinkingSupportDetectedEvent &&
                event.provider == resolvedProvider &&
                event.model == currentModel
            ) {
                supportsReasoning = event.supportsThinking
            }
        }
    }

    LaunchedEffect(resolvedProvider, currentModel) {
        reasoningEffort = if (resolvedProvider != null && currentModel.isNotBlank()) {
            ModelCapabilitiesCache.getReasoningLevel(resolvedProvider, currentModel)
        } else {
            ReasoningEffort.DEFAULT
        }
    }

    LaunchedEffect(reasoningEffort) {
        if (supportsReasoning && resolvedProvider != null && currentModel.isNotBlank()) {
            ModelCapabilitiesCache.setReasoningLevel(resolvedProvider, currentModel, reasoningEffort)
            EventBus.emit(
                ReasoningEffortChangedEvent(
                    provider = resolvedProvider,
                    model = currentModel,
                    newEffort = reasoningEffort,
                ),
            )
        }
    }

    actionInputField(
        value = value,
        onValueChange = onValueChange,
        onSend = onSend,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        isLoading = isLoading,
        minLines = minLines,
        maxLines = maxLines,
        error = error,
        sendContentDescription = sendContentDescription,
        inlineControls = {
            if (supportsReasoning) {
                Box {
                    themedTooltip(text = stringResource("chat.reasoning.effort.tooltip")) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .clickable(
                                    enabled = enabled && !isLoading,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { showReasoningDropdown = true },
                                )
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    start = Spacing.small,
                                    end = Spacing.small,
                                    top = 3.dp,
                                    bottom = 3.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource("chat.reasoning.effort.label") + ":",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                )
                                Text(
                                    text = reasoningEffort.value.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    dropdownMenu(
                        expanded = showReasoningDropdown,
                        onDismissRequest = { showReasoningDropdown = false },
                    ) {
                        ReasoningEffort.entries.forEach { effort ->
                            val label = when (effort) {
                                ReasoningEffort.OFF -> stringResource("chat.reasoning.effort.off")
                                ReasoningEffort.LOW -> stringResource("chat.reasoning.effort.low")
                                ReasoningEffort.MEDIUM -> stringResource("chat.reasoning.effort.medium")
                                ReasoningEffort.HIGH -> stringResource("chat.reasoning.effort.high")
                            }
                            val description = when (effort) {
                                ReasoningEffort.OFF -> stringResource("chat.reasoning.effort.off.description")
                                ReasoningEffort.LOW -> stringResource("chat.reasoning.effort.low.description")
                                ReasoningEffort.MEDIUM -> stringResource("chat.reasoning.effort.medium.description")
                                ReasoningEffort.HIGH -> stringResource("chat.reasoning.effort.high.description")
                            }
                            themedTooltip(text = description) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (effort == reasoningEffort) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    trailingIcon = null,
                                    onClick = {
                                        reasoningEffort = effort
                                        showReasoningDropdown = false
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    colors = AppComponents.menuItemColors(),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
