/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.plan

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.askimo.core.i18n.LocalizationManager
import io.askimo.core.plan.PlanStepEvent
import io.askimo.core.plan.domain.PlanInput
import io.askimo.core.util.TimeUtil
import io.askimo.ui.common.components.primaryButton
import io.askimo.ui.common.components.secondaryButton
import io.askimo.ui.common.i18n.stringResource
import io.askimo.ui.common.preferences.ApplicationPreferences
import io.askimo.ui.common.theme.AppComponents
import io.askimo.ui.common.theme.Spacing
import io.askimo.ui.common.theme.ThemePreferences
import io.askimo.ui.common.ui.markdownText
import io.askimo.ui.common.ui.themedTooltip
import io.askimo.ui.common.ui.util.FileDialogUtils
import io.askimo.ui.plan.PlanExportService.ExportFormat
import io.askimo.ui.plan.PlanExportService.ExportMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// Characters revealed per tick during the simulated-streaming animation.
private const val STREAM_CHUNK_SIZE = 12

// Delay between ticks — lower = faster reveal.
private val STREAM_TICK = 16.milliseconds

@Composable
fun planDetailView(
    viewModel: PlansViewModel,
    onBack: () -> Unit,
    onEditPlan: (() -> Unit)? = null,
    onDuplicatePlan: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val plan = viewModel.selectedPlan ?: return
    val scrollState = rememberScrollState()

    var historyPanelExpanded by remember {
        mutableStateOf(ApplicationPreferences.getPlanHistorySidePanelExpanded())
    }
    LaunchedEffect(historyPanelExpanded) {
        ApplicationPreferences.setPlanHistorySidePanelExpanded(historyPanelExpanded)
    }

    // Track whether the user has manually scrolled up; if so, stop auto-scrolling.
    var userScrolledUp by remember { mutableStateOf(false) }

    // When the user manually scrolls, detect direction.
    LaunchedEffect(scrollState.value) {
        if (scrollState.isScrollInProgress) {
            val distanceFromBottom = scrollState.maxValue - scrollState.value
            userScrolledUp = distanceFromBottom > 100
        }
    }

    // Whenever content grows (maxValue increases) and the user hasn't scrolled up,
    // pin to the bottom. This fires on every new step output or result update.
    LaunchedEffect(scrollState.maxValue) {
        if (!userScrolledUp) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // When a new run starts (isRunning flips to true), reset scroll position to bottom.
    LaunchedEffect(viewModel.isRunning) {
        if (viewModel.isRunning) {
            userScrolledUp = false
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // ── Sticky header: nav + plan title/description + action buttons ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 24.dp, end = 36.dp, top = 8.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ThemePreferences.CONTENT_MAX_WIDTH)
                        .fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource("action.back"),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Text(
                                text = stringResource("plans.title"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                        modifier = Modifier.padding(bottom = Spacing.extraSmall),
                    ) {
                        Text(
                            text = plan.icon.ifBlank { "📋" },
                            style = MaterialTheme.typography.headlineLarge,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = plan.name,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            if (plan.description.isNotBlank()) {
                                SelectionContainer {
                                    Text(
                                        text = plan.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        if (onEditPlan != null && !plan.builtIn) {
                            themedTooltip(text = stringResource("plans.editor.menu.edit")) {
                                IconButton(
                                    onClick = onEditPlan,
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource("plans.editor.menu.edit"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        if (onDuplicatePlan != null && plan.builtIn) {
                            themedTooltip(text = stringResource("plans.duplicate")) {
                                IconButton(
                                    onClick = onDuplicatePlan,
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = stringResource("plans.duplicate"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // ── Scrollable body ───────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            .padding(start = 24.dp, end = 36.dp, top = 16.dp, bottom = 24.dp),
                    ) {
                        var stepsExpanded by remember { mutableStateOf(false) }
                        val stepsTooltip = remember(plan.steps) {
                            plan.steps.entries.mapIndexed { i, (stepId, _) ->
                                "${i + 1}. $stepId"
                            }.joinToString("\n")
                        }
                        Spacer(modifier = Modifier.height(Spacing.small))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column {
                                themedTooltip(
                                    text = if (stepsExpanded) "" else stepsTooltip,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .toggleable(
                                                value = stepsExpanded,
                                                role = Role.Button,
                                                onValueChange = { stepsExpanded = it },
                                            )
                                            .padding(horizontal = Spacing.large, vertical = Spacing.medium),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource("plans.detail.how.it.works") +
                                                " (${plan.steps.size} ${if (plan.steps.size == 1) stringResource("plans.detail.step") else stringResource("plans.detail.steps")})",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Icon(
                                            imageVector = if (stepsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                if (stepsExpanded) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    Column(
                                        modifier = Modifier.padding(Spacing.large),
                                        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                                    ) {
                                        plan.steps.entries.forEachIndexed { index, (stepId, step) ->
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Surface(
                                                    shape = MaterialTheme.shapes.extraSmall,
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                ) {
                                                    Text(
                                                        text = "${index + 1}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stepId,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    if (!step.system.isNullOrBlank()) {
                                                        Text(
                                                            text = step.system!!,
                                                            style = MaterialTheme.typography.bodySmall.copy(
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 11.sp,
                                                            ),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                            modifier = Modifier.padding(top = 2.dp),
                                                        )
                                                    }
                                                    Text(
                                                        text = step.message,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(top = 2.dp),
                                                    )
                                                }
                                            }
                                            if (index < plan.steps.size - 1) {
                                                HorizontalDivider(
                                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.extraLarge))

                        if (plan.inputs.isNotEmpty()) {
                            Text(
                                text = stringResource("plans.detail.inputs"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(bottom = Spacing.medium),
                            )
                            plan.inputs.forEach { input ->
                                planInputField(
                                    input = input,
                                    value = viewModel.inputValues[input.key] ?: input.default,
                                    onValueChange = { viewModel.updateInput(input.key, it) },
                                    error = viewModel.inputErrors[input.key],
                                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.large),
                                )
                            }
                            Spacer(modifier = Modifier.height(Spacing.medium))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            primaryButton(
                                onClick = {
                                    viewModel.clearResult()
                                    viewModel.runPlan()
                                },
                                enabled = !viewModel.isRunning,
                            ) {
                                if (viewModel.isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.size(Spacing.small))
                                Text(
                                    text = if (viewModel.isRunning) {
                                        stringResource("plans.running")
                                    } else {
                                        stringResource("plans.run")
                                    },
                                )
                            }
                            if (!viewModel.isRunning && (viewModel.runResult != null || viewModel.runError != null || viewModel.stepProgress.isNotEmpty())) {
                                secondaryButton(
                                    onClick = { viewModel.clearResult() },
                                ) {
                                    Text(
                                        text = stringResource("plans.clear.result"),
                                    )
                                }
                            }
                        }

                        if (viewModel.stepProgress.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(Spacing.large))
                            val lastCompletedStepName = if (viewModel.runResult != null) {
                                viewModel.stepProgress
                                    .filterIsInstance<PlanStepEvent.Completed>()
                                    .lastOrNull()
                                    ?.stepName
                            } else {
                                null
                            }
                            agenticStepProgressPanel(
                                steps = viewModel.stepProgress,
                                suppressOutputForStepName = lastCompletedStepName,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // Inline question panel — shown when the executor pauses for user input
                        viewModel.pendingQuestion?.let { pending ->
                            Spacer(modifier = Modifier.height(Spacing.medium))
                            interactiveQuestionPanel(
                                question = pending.question,
                                answerText = viewModel.pendingAnswerText,
                                onAnswerChange = { viewModel.updatePendingAnswer(it) },
                                onSubmit = { viewModel.answerQuestion() },
                                onSkip = { viewModel.skipQuestion() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        viewModel.runError?.let { error ->
                            Spacer(modifier = Modifier.height(Spacing.large))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Column(modifier = Modifier.padding(Spacing.large)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                        SelectionContainer {
                                            Text(
                                                text = error,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(Spacing.medium))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                    ) {
                                        secondaryButton(
                                            onClick = {
                                                viewModel.clearResult()
                                                viewModel.runPlan()
                                            },
                                        ) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(modifier = Modifier.size(Spacing.extraSmall))
                                            Text(
                                                text = stringResource("plans.retry"),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val currentResult = viewModel.runResult
                        val pinned = viewModel.pinnedResult
                        if (currentResult != null || pinned != null) {
                            Spacer(modifier = Modifier.height(Spacing.large))
                            if (currentResult != null && pinned != null && pinned.executionId != currentResult.executionId) {
                                // Side-by-side comparison
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                                ) {
                                    resultPanel(
                                        output = pinned.output,
                                        title = stringResource("plans.result.pinned"),
                                        isPinned = true,
                                        onUnpin = { viewModel.unpinResult() },
                                        viewModel = viewModel,
                                        planName = plan.name,
                                        showExport = false,
                                        executionId = pinned.executionId,
                                        modifier = Modifier.weight(1f),
                                    )
                                    resultPanel(
                                        output = currentResult.output,
                                        title = stringResource("plans.result.title"),
                                        isPinned = false,
                                        onUnpin = null,
                                        viewModel = viewModel,
                                        planName = plan.name,
                                        showExport = true,
                                        executionId = currentResult.executionId,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else if (currentResult != null) {
                                resultPanel(
                                    output = currentResult.output,
                                    title = stringResource("plans.result.title"),
                                    isPinned = false,
                                    onUnpin = null,
                                    viewModel = viewModel,
                                    planName = plan.name,
                                    showExport = true,
                                    executionId = currentResult.executionId,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (currentResult != null && !viewModel.isRunning) {
                                Spacer(modifier = Modifier.height(Spacing.medium))
                                followUpPanel(
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = Spacing.extraSmall),
                    style = AppComponents.scrollbarStyle(),
                )
            } // close scrollable Box
        } // close outer Column(weight(1f))

        planHistorySidePanel(
            executions = viewModel.executions,
            onDeleteExecution = { viewModel.deleteExecution(it) },
            onRestoreInputs = { viewModel.restoreInputs(it) },
            onPinExecution = { exec ->
                if (viewModel.pinnedResult?.executionId == exec.id) {
                    viewModel.unpinResult()
                } else {
                    viewModel.pinExecution(exec)
                }
            },
            pinnedExecutionId = viewModel.pinnedResult?.executionId,
            isExpanded = historyPanelExpanded,
            onExpandedChange = { historyPanelExpanded = it },
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

@Composable
private fun agenticStepProgressPanel(
    steps: List<PlanStepEvent>,
    suppressOutputForStepName: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(Spacing.large)) {
            Text(
                text = stringResource("plans.steps.title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.medium),
            )

            val orderedSteps = linkedMapOf<String, PlanStepEvent>()
            steps.forEach { event ->
                val existing = orderedSteps[event.stepName]
                if (existing == null || event !is PlanStepEvent.Started) {
                    orderedSteps[event.stepName] = event
                }
            }

            orderedSteps.values.forEachIndexed { index, event ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.small),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    )
                }
                agenticStepRow(
                    event = event,
                    suppressOutput = event.stepName == suppressOutputForStepName,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun agenticStepRow(
    event: PlanStepEvent,
    suppressOutput: Boolean = false,
) {
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var showCopyFeedback by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }

    var outputExpanded by remember { mutableStateOf(true) }

    var dotCount by remember { mutableIntStateOf(1) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(event) {
        if (event is PlanStepEvent.Started) {
            val startMs = event.timestamp.toEpochMilli()
            while (true) {
                delay(500.milliseconds)
                dotCount = if (dotCount >= 3) 1 else dotCount + 1
                elapsedSeconds = (System.currentTimeMillis() - startMs) / 1000
            }
        }
    }

    val copyableOutput: String? = if (event is PlanStepEvent.Completed) {
        event.output?.toString()?.trim()?.takeIf { it.isNotBlank() }
    } else {
        null
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.padding(top = 2.dp)) {
                when (event) {
                    is PlanStepEvent.Started -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary,
                    )

                    is PlanStepEvent.WaitingForInput -> Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )

                    is PlanStepEvent.Completed -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp),
                    )

                    is PlanStepEvent.Failed -> Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = event.stepName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (event) {
                            is PlanStepEvent.Completed -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            text = TimeUtil.formatDurationMs(
                                                durationMs = event.durationMs,
                                                hourLabel = stringResource("plans.steps.duration.hour"),
                                                minuteLabel = stringResource("plans.steps.duration.minute"),
                                                secondLabel = stringResource("plans.steps.duration.second"),
                                                lessThanOne = stringResource("plans.steps.duration.less.than.one.second"),
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                    stepTokenUsageLabel(event)?.let { usage ->
                                        if (isHovered) {
                                            themedTooltip(text = stringResource("message.token.usage.tooltip")) {
                                                Text(
                                                    text = usage,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is PlanStepEvent.Failed -> {
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.errorContainer,
                                ) {
                                    Text(
                                        text = TimeUtil.formatDurationMs(
                                            durationMs = event.durationMs,
                                            hourLabel = stringResource("plans.steps.duration.hour"),
                                            minuteLabel = stringResource("plans.steps.duration.minute"),
                                            secondLabel = stringResource("plans.steps.duration.second"),
                                            lessThanOne = stringResource("plans.steps.duration.less.than.one.second"),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }

                            else -> Unit
                        }
                    }

                    if (!suppressOutput && event is PlanStepEvent.Completed && !copyableOutput.isNullOrBlank()) {
                        IconButton(
                            onClick = { outputExpanded = !outputExpanded },
                            modifier = Modifier.size(20.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = if (outputExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (outputExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                when (event) {
                    is PlanStepEvent.Completed -> {
                        if (!suppressOutput && !copyableOutput.isNullOrBlank() && outputExpanded) {
                            markdownText(
                                markdown = copyableOutput,
                                modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
                            )
                        }
                    }

                    is PlanStepEvent.WaitingForInput -> {
                        Text(
                            text = event.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    is PlanStepEvent.Failed -> {
                        SelectionContainer {
                            Text(
                                text = event.error.message ?: event.error.javaClass.simpleName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    is PlanStepEvent.Started -> {
                        val elapsed = if (elapsedSeconds > 0) {
                            val m = elapsedSeconds / 60
                            val s = elapsedSeconds % 60
                            " (${if (m > 0) "${m}m " else ""}${s}s)"
                        } else {
                            ""
                        }
                        Text(
                            text = stringResource("plans.steps.running") + elapsed + ".".repeat(dotCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (!suppressOutput && copyableOutput != null && outputExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                themedTooltip(text = stringResource("message.copy")) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(copyableOutput))
                            showCopyFeedback = true
                            coroutineScope.launch {
                                delay(2000.milliseconds)
                                showCopyFeedback = false
                            }
                        },
                        modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource("message.copy.description"),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showCopyFeedback) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource("mermaid.feedback.copied"),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun stepTokenUsageLabel(event: PlanStepEvent.Completed): String? {
    val total = event.totalTokens
    val input = event.inputTokens
    val output = event.outputTokens
    if (total == null || total <= 0) return null

    return if (input != null && output != null) {
        "${LocalizationManager.formatNumber(total)} tokens (${LocalizationManager.formatNumber(input)} in / ${LocalizationManager.formatNumber(output)} out)"
    } else {
        "${LocalizationManager.formatNumber(total)} tokens"
    }
}

@Composable
private fun planTotalSummaryLabel(viewModel: PlansViewModel): String? {
    val total = viewModel.planTotalTokens
    val input = viewModel.planTotalInputTokens
    val output = viewModel.planTotalOutputTokens
    val durationMs = viewModel.planTotalDurationMs
    if (total == null || total <= 0) return null

    return buildString {
        if (input != null && output != null) {
            append("${LocalizationManager.formatNumber(total)} tokens (${LocalizationManager.formatNumber(input)} in / ${LocalizationManager.formatNumber(output)} out)")
        } else {
            append("${LocalizationManager.formatNumber(total)} tokens")
        }
        if (durationMs != null && durationMs > 0) {
            val durationLabel = when {
                durationMs < 1_000 -> "${durationMs}ms"
                else -> "${"%.1f".format(durationMs / 1000.0)}s"
            }
            append(" · $durationLabel")
        }
    }
}

/**
 * Inline panel rendered while the plan executor is paused waiting for the user to answer
 * an interactive [io.askimo.core.plan.domain.WorkflowNode.Ask] step.
 *
 * Submitting calls [onSubmit] which delivers the answer to [PlansViewModel.answerQuestion],
 * unblocking the executor and resuming plan execution.
 */
@Composable
private fun interactiveQuestionPanel(
    question: String,
    answerText: String,
    onAnswerChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(Spacing.large)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = Spacing.small),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource("plans.interactive.question.label"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(bottom = Spacing.medium),
            )
            planInputField(
                value = answerText,
                onValueChange = onAnswerChange,
                onSend = onSubmit,
                placeholder = stringResource("plans.interactive.answer.placeholder"),
                sendContentDescription = stringResource("plans.interactive.send"),
            )
            TextButton(
                onClick = onSkip,
                modifier = Modifier.padding(top = Spacing.extraSmall).pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(
                    text = stringResource("plans.interactive.skip"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Follow-up input panel — appears below the result so the user can ask the AI to
 * refine or extend the current output without re-running the full plan workflow.
 */
@Composable
private fun followUpPanel(
    viewModel: PlansViewModel,
    modifier: Modifier = Modifier,
) {
    val isActive = viewModel.isFollowingUp

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(Spacing.large)) {
            Text(
                text = stringResource("plans.followup.label"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.small),
            )
            planInputField(
                value = viewModel.followUpText,
                onValueChange = { viewModel.updateFollowUpText(it) },
                onSend = { viewModel.runFollowUp() },
                placeholder = stringResource("plans.followup.placeholder"),
                enabled = !isActive,
                isLoading = isActive,
                error = viewModel.followUpError,
                sendContentDescription = stringResource("plans.followup.send"),
            )
        }
    }
}

@Composable
private fun resultPanel(
    output: String,
    title: String,
    isPinned: Boolean,
    onUnpin: (() -> Unit)?,
    viewModel: PlansViewModel,
    planName: String,
    showExport: Boolean,
    executionId: String? = null,
    modifier: Modifier = Modifier,
) {
    // Simulate streaming: reveal the output gradually after it arrives.
    var displayedLength by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(output) {
        // When a new (non-empty) output arrives start from zero and animate forward.
        // Pinned/comparison panels skip animation (isPinned) to avoid jarring re-plays.
        if (output.isNotBlank() && !isPinned) {
            displayedLength = 0
            coroutineScope.launch {
                while (displayedLength < output.length) {
                    delay(STREAM_TICK)
                    displayedLength = minOf(displayedLength + STREAM_CHUNK_SIZE, output.length)
                }
            }
        } else {
            displayedLength = output.length
        }
    }

    val displayedOutput = if (displayedLength >= output.length) output else output.take(displayedLength)
    val isStreaming = displayedLength < output.length
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 2.dp,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(Spacing.large)) {
            var exportMenuExpanded by remember { mutableStateOf(false) }
            var showCopyFeedback by remember { mutableStateOf(false) }
            val clipboardManager = LocalClipboardManager.current

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Title + plan totals on hover
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (isPinned) Icons.Default.PushPin else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isPinned) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Plan-level totals — only for the live (non-pinned) result panel
                    if (!isPinned) {
                        planTotalSummaryLabel(viewModel)?.let { summary ->
                            themedTooltip(text = stringResource("message.token.usage.tooltip")) {
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isPinned && onUnpin != null) {
                        themedTooltip(text = stringResource("plans.result.unpin")) {
                            IconButton(
                                onClick = onUnpin,
                                modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource("plans.result.unpin"),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    themedTooltip(text = stringResource("message.copy")) {
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(output))
                                showCopyFeedback = true
                                coroutineScope.launch {
                                    delay(2000.milliseconds)
                                    showCopyFeedback = false
                                }
                            },
                            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = if (showCopyFeedback) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                                contentDescription = stringResource("message.copy.description"),
                                modifier = Modifier.size(14.dp),
                                tint = if (showCopyFeedback) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (showExport) {
                        Box {
                            TextButton(
                                onClick = { exportMenuExpanded = true },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(text = stringResource("plans.export"), style = MaterialTheme.typography.labelMedium)
                            }
                            AppComponents.dropdownMenu(
                                expanded = exportMenuExpanded,
                                onDismissRequest = { exportMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource("plans.export.result.pdf")) },
                                    onClick = {
                                        exportMenuExpanded = false
                                        coroutineScope.launch { triggerExport(viewModel, planName, ExportMode.RESULT_ONLY, ExportFormat.PDF) }
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource("plans.export.result.word")) },
                                    onClick = {
                                        exportMenuExpanded = false
                                        coroutineScope.launch { triggerExport(viewModel, planName, ExportMode.RESULT_ONLY, ExportFormat.WORD) }
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource("plans.export.fullrun.pdf")) },
                                    onClick = {
                                        exportMenuExpanded = false
                                        coroutineScope.launch { triggerExport(viewModel, planName, ExportMode.FULL_RUN, ExportFormat.PDF) }
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource("plans.export.fullrun.word")) },
                                    onClick = {
                                        exportMenuExpanded = false
                                        coroutineScope.launch { triggerExport(viewModel, planName, ExportMode.FULL_RUN, ExportFormat.WORD) }
                                    },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                )
                            }
                        }
                    }
                }
            }

            if (showExport) {
                viewModel.exportError?.let { err ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.small),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Text(text = err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(bottom = Spacing.medium))
            markdownText(
                markdown = if (isStreaming) "$displayedOutput▍" else displayedOutput,
                modifier = Modifier.fillMaxWidth(),
                messageId = executionId,
            )
        }
    }
}

private suspend fun triggerExport(
    viewModel: PlansViewModel,
    planName: String,
    mode: ExportMode,
    format: ExportFormat,
) {
    val extension = when (format) {
        ExportFormat.PDF -> "pdf"
        ExportFormat.WORD -> "docx"
    }

    val targetFile = FileDialogUtils.pickSavePath(
        suggestedName = planName.replace(" ", "_"),
        extension = extension,
        title = "Export Plan",
    ) ?: return
    viewModel.exportPlan(targetFile, mode, format) { _ -> }
}

@Composable
private fun planInputField(
    input: PlanInput,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        when (input.type) {
            "toggle" -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = value.lowercase() == "true",
                            role = Role.Switch,
                            onValueChange = { onValueChange(it.toString()) },
                        )
                        .padding(vertical = Spacing.small),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = input.label + if (input.required) " *" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (input.hint.isNotBlank()) {
                            Text(
                                text = input.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = value.lowercase() == "true",
                        onCheckedChange = { onValueChange(it.toString()) },
                    )
                }
            }

            "multiline" -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(input.label + if (input.required) " *" else "") },
                    placeholder = if (input.hint.isNotBlank()) {
                        { Text(input.hint) }
                    } else {
                        null
                    },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
            }

            "number" -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(input.label + if (input.required) " *" else "") },
                    placeholder = if (input.hint.isNotBlank()) {
                        { Text(input.hint) }
                    } else {
                        null
                    },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = AppComponents.outlinedTextFieldColors(),
                )
            }

            else -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(input.label + if (input.required) " *" else "") },
                    placeholder = if (input.hint.isNotBlank()) {
                        { Text(input.hint) }
                    } else {
                        null
                    },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = AppComponents.outlinedTextFieldColors(),
                )
            }
        }
    }
}
