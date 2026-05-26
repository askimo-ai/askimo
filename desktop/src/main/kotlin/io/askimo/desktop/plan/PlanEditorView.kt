/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.askimo.core.AppConstants.DOMAIN
import io.askimo.ui.common.components.linkButton
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.components.sendTextField
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.plan.PlansViewModel
import java.awt.Desktop
import java.net.URI

/**
 * YAML editor view for creating and editing plans.
 *
 * New-plan mode includes an AI generation panel: the user describes the plan in
 * plain English and the active chat model generates the YAML, which lands directly
 * in the editor for review and editing before saving.
 */
@Composable
fun planEditorView(
    viewModel: PlansViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNewPlan = viewModel.editingPlanId == null
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Header bar ────────────────────────────────────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    IconButton(
                        onClick = {
                            viewModel.cancelEdit()
                            onBack()
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource("action.back"),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = if (isNewPlan) {
                            stringResource("plans.editor.title.new")
                        } else {
                            stringResource("plans.editor.title.edit")
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryButton(
                        onClick = {
                            viewModel.cancelEdit()
                            onBack()
                        },
                    ) {
                        Text(text = stringResource("action.cancel"))
                    }
                    primaryButton(
                        onClick = { viewModel.savePlan(onBack) },
                        enabled = viewModel.editorValidationError == null && !viewModel.isSaving,
                    ) {
                        if (viewModel.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.size(Spacing.small))
                        Text(stringResource("action.save"))
                    }
                }
            }
        }

        // ── Validation banner ─────────────────────────────────────────────────
        val validationError = viewModel.editorValidationError
        val saveError = viewModel.saveError

        when {
            saveError != null -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    SelectionContainer {
                        Text(
                            text = saveError,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = Spacing.small),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            validationError != null -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    SelectionContainer {
                        Text(
                            text = "❌ $validationError",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = Spacing.small),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            viewModel.editorYaml.isNotBlank() -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = Spacing.small),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource("plans.editor.valid"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 1400.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = Spacing.large),
                horizontalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                // ── Left: AI generation panel (new plans only) + YAML editor ──────
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    // AI generation panel — only shown when creating a new plan
                    if (isNewPlan) {
                        aiGenerationPanel(viewModel = viewModel)
                        Spacer(modifier = Modifier.height(Spacing.large))
                    }

                    Text(
                        text = stringResource("plans.editor.label"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.small),
                    )
                    OutlinedTextField(
                        value = viewModel.editorYaml,
                        onValueChange = { viewModel.updateEditorYaml(it) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        ),
                        placeholder = {
                            Text(
                                text = if (isNewPlan) YAML_HINT else stringResource("plans.editor.placeholder"),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        },
                        isError = viewModel.editorValidationError != null,
                        colors = AppComponents.outlinedTextFieldColors(),
                        shape = MaterialTheme.shapes.small,
                    )
                }

                // ── Right: hint panel + docs link ─────────────────────────────────
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    // Header row: "Reference" label + docs link
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.small),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource("plans.editor.hint.title"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        linkButton(
                            onClick = {
                                runCatching {
                                    Desktop.getDesktop().browse(URI("https://$DOMAIN/docs/desktop/plans/"))
                                }
                            },
                        ) {
                            Text(
                                text = stringResource("plans.editor.docs.link"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = YAML_HINT,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 18.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.medium),
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.medium))
                    Text(
                        text = stringResource("plans.editor.hint.fields"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

/**
 * AI-assisted YAML generation panel.
 *
 * The user describes their plan in plain English; pressing Generate (or ⌘/Ctrl+Enter)
 * calls the active chat model and drops the result directly into the YAML editor.
 */
@Composable
private fun aiGenerationPanel(
    viewModel: PlansViewModel,
    modifier: Modifier = Modifier,
) {
    val isGenerating = viewModel.isGeneratingYaml

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(Spacing.large)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = Spacing.small),
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource("plans.editor.ai.label"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            sendTextField(
                value = viewModel.aiPromptText,
                onValueChange = { viewModel.updateAiPrompt(it) },
                onSend = { viewModel.generateYamlFromPrompt() },
                placeholder = stringResource("plans.editor.ai.placeholder"),
                enabled = !isGenerating,
                isLoading = isGenerating,
                error = viewModel.aiGenerateError,
                sendContentDescription = stringResource("plans.editor.ai.generate"),
            )

            if (!isGenerating && viewModel.aiGenerateError == null) {
                Text(
                    text = stringResource("plans.editor.ai.hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = Spacing.extraSmall),
                )
            }
        }
    }
}

private val YAML_HINT = """
id: my-plan
name: My Plan
description: Optional description
icon: "📊"  # must be an emoji, e.g. 💡 ✍️ 🔍 📋

inputs:
  - key: topic
    label: Topic
    required: true
    type: text      # text|multiline|toggle|number
    default: ""
    hint: Enter a topic

steps:
  - id: research
    system: "You are a researcher."
    message: "Research {{topic}}"
  - id: report
    message: "Write report using {{research}}"

# Optional explicit workflow
# (omit for auto-sequential)
workflow:
  type: sequence
  nodes:
    - type: step
      stepId: research
    - type: step
      stepId: report
""".trimIndent()
