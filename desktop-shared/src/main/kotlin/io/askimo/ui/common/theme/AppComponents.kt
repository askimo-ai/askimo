/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.askimo.ui.common.i18n.stringResource

/**
 * Composable widgets and layout tokens shared across the app — dialogs, dropdown menus,
 * form fields, cards, spinners, popups. For color tokens (backgrounds, borders, content
 * colors), see [AppColors]; this object consumes those tokens but doesn't define new ones.
 */
object AppComponents {

    // Shared spacing/tokens for scaffold-style dialogs.
    val dialogContentPadding: Dp = 24.dp
    val dialogSectionSpacing: Dp = 16.dp
    val dialogActionBarMinHeight: Dp = 56.dp
    val dialogScrollbarPadding: Dp = 12.dp

    // ── Navigation ───────────────────────────────────────────────────────────

    /** Corner radius for the selected-item background in all navigation components. */
    val navigationItemShape: Shape = RoundedCornerShape(8.dp)

    // ── Cards ─────────────────────────────────────────────────────────────────

    /**
     * A card with standardized hover behavior across all themes.
     *
     * Bakes in:
     * - `.clip(shape)` **before** `hoverable`/`clickable` so the ripple and hover
     *   highlight are always clipped to rounded corners (prevents the rectangle-border bug).
     * - Default: [AppColors.Elevation.RAISED] + [AppColors.codeBlockBorderColor] (`outlineVariant`) border.
     * - Hover:   [AppColors.Elevation.SELECTED] + `primary.copy(alpha = 0.4f)` border — the
     *   same tier used for an actually-selected item elsewhere, so hover and selection never
     *   read as two different colors.
     *
     * Use this instead of bare [Card] whenever the card is clickable.
     */
    @Composable
    fun clickableCard(
        onClick: (() -> Unit)?,
        modifier: Modifier = Modifier,
        shape: Shape = MaterialTheme.shapes.medium,
        colors: CardColors = AppColors.cardColors(AppColors.Elevation.RAISED),
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isHovered by interactionSource.collectIsHoveredAsState()

        val resolvedColors = if (onClick != null && isHovered) AppColors.cardColors(AppColors.Elevation.SELECTED) else colors

        val border = BorderStroke(
            1.dp,
            if (onClick != null && isHovered) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            } else {
                AppColors.codeBlockBorderColor()
            },
        )

        Card(
            modifier = modifier
                .clip(shape)
                .then(
                    if (onClick != null) {
                        Modifier
                            .hoverable(interactionSource)
                            .clickable(onClick = onClick)
                            .pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    },
                ),
            shape = shape,
            colors = resolvedColors,
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            content = content,
        )
    }

    // ── Inputs ────────────────────────────────────────────────────────────────

    /**
     * Themed [OutlinedTextField] with default colors from [AppColors.outlinedTextFieldColors].
     */
    @Composable
    fun appOutlinedTextField(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        label: (@Composable () -> Unit)? = null,
        placeholder: (@Composable () -> Unit)? = null,
        leadingIcon: (@Composable () -> Unit)? = null,
        trailingIcon: (@Composable () -> Unit)? = null,
        supportingText: (@Composable () -> Unit)? = null,
        isError: Boolean = false,
        enabled: Boolean = true,
        readOnly: Boolean = false,
        singleLine: Boolean = false,
        maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
        keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
        keyboardActions: KeyboardActions = KeyboardActions.Default,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            supportingText = supportingText,
            isError = isError,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            colors = AppColors.outlinedTextFieldColors(),
        )
    }

    /**
     * A themed [OutlinedTextField] for secret values (API keys, passwords).
     *
     * Renders as a password field by default and provides an inline eye-icon toggle
     * so the user can reveal the actual value they typed. Visibility state is local
     * to each call site and resets whenever the composable leaves the composition.
     *
     * All other behaviour (colors, debounce, etc.) is left to the caller.
     */
    @Composable
    fun appSecretTextField(
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        label: (@Composable () -> Unit)? = null,
        placeholder: (@Composable () -> Unit)? = null,
        supportingText: (@Composable () -> Unit)? = null,
        isError: Boolean = false,
        enabled: Boolean = true,
        singleLine: Boolean = true,
    ) {
        var showSecret by remember { mutableStateOf(false) }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            placeholder = placeholder,
            supportingText = supportingText,
            isError = isError,
            enabled = enabled,
            singleLine = singleLine,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    onClick = { showSecret = !showSecret },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (showSecret) "mcp.instance.password.hide" else "mcp.instance.password.show",
                        ),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = AppColors.outlinedTextFieldColors(),
        )
    }

    // ── Form Fields ───────────────────────────────────────────────────────────

    /**
     * Standardised form-field layout that enforces a consistent vertical rhythm:
     *
     * ```
     * [Label]           ┐
     * [Description]     ┘ extraSmall gap — label and description belong together
     *                     small gap — breath before the interactive control
     * [content slot]    ← text field, secret field, button row, etc.
     * [hint slot]         extraSmall gap — hint is a sub-annotation of the input
     * ```
     *
     *
     * @param label       Field label rendered in [AppTextStyles.groupTitle].
     * @param description One-line helper text rendered in [AppTextStyles.caption].
     * @param required    When `true`, appends " *" to the label.
     * @param hint        Optional composable rendered below [content] with [Spacing.extraSmall]
     *                    top gap (e.g. the currently-selected option description for a SelectField).
     * @param content     The interactive control (text field, button row, etc.).
     */
    @Composable
    fun formField(
        label: String,
        description: String,
        modifier: Modifier = Modifier,
        required: Boolean = false,
        hint: (@Composable () -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = if (required) "$label *" else label,
                style = AppTextStyles.groupTitle,
            )
            Spacer(Modifier.height(Spacing.extraSmall))
            Text(text = description, style = AppTextStyles.caption)
            Spacer(Modifier.height(Spacing.small))
            content()
            if (hint != null) {
                Spacer(Modifier.height(Spacing.extraSmall))
                hint()
            }
        }
    }

    /**
     * A themed [DropdownMenuItem] with consistent UX across the app:
     * - Always reserves leading-icon space for a [Check] mark, rendered transparently
     *   when not selected — keeping all item texts left-aligned regardless of selection.
     * - Shows a hand [PointerIcon] on hover.
     * - Optionally renders a subtle [HorizontalDivider] below the item — pass
     *   `showDivider = false` for the last item in a list.
     */
    @Composable
    fun themedDropdownMenuItem(
        text: @Composable () -> Unit,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        isSelected: Boolean = false,
        showDivider: Boolean = false,
        enabled: Boolean = true,
        trailingIcon: (@Composable () -> Unit)? = null,
        contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        DropdownMenuItem(
            text = text,
            onClick = onClick,
            modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
            enabled = enabled,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = if (isSelected) "Selected" else null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                )
            },
            trailingIcon = trailingIcon,
            colors = AppColors.menuItemColors(),
            contentPadding = contentPadding,
        )
        if (showDivider) {
            HorizontalDivider(color = AppColors.codeBlockBorderColor())
        }
    }

    @Composable
    fun dropdownMenu(
        expanded: Boolean,
        onDismissRequest: () -> Unit,
        modifier: Modifier = Modifier,
        offset: DpOffset = DpOffset.Zero,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val border = AppColors.popupBorderStroke()
        MaterialTheme(colorScheme = AppColors.popupColorScheme()) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismissRequest,
                offset = offset,
                modifier = modifier.border(
                    width = border.width,
                    brush = border.brush,
                    shape = RoundedCornerShape(4.dp),
                ),
                content = content,
            )
        }
    }

    @Composable
    fun alertDialog(
        onDismissRequest: () -> Unit,
        confirmButton: @Composable () -> Unit,
        modifier: Modifier = Modifier,
        dismissButton: @Composable (() -> Unit)? = null,
        icon: @Composable (() -> Unit)? = null,
        title: @Composable (() -> Unit)? = null,
        text: @Composable (() -> Unit)? = null,
        shape: Shape = AlertDialogDefaults.shape,
        containerColor: Color = MaterialTheme.colorScheme.surface,
        iconContentColor: Color = AlertDialogDefaults.iconContentColor,
        titleContentColor: Color = AlertDialogDefaults.titleContentColor,
        textContentColor: Color = AlertDialogDefaults.textContentColor,
        tonalElevation: Dp = AppColors.popupSurfaceTonalElevation,
        properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val border = AppColors.popupBorderStroke()
        MaterialTheme(colorScheme = AppColors.popupColorScheme()) {
            AlertDialog(
                onDismissRequest = onDismissRequest,
                confirmButton = confirmButton,
                modifier = modifier.border(border.width, border.brush, shape),
                dismissButton = dismissButton,
                icon = icon,
                title = title,
                text = text,
                shape = shape,
                containerColor = containerColor,
                iconContentColor = iconContentColor,
                titleContentColor = titleContentColor,
                textContentColor = textContentColor,
                tonalElevation = tonalElevation,
                properties = properties,
            )
        }
    }

    /** Shared title-bar + optional sticky-header slot used by both scaffold dialog variants. */
    @Composable
    private fun scaffoldDialogHeader(
        title: (@Composable () -> Unit)?,
        onCloseRequest: (() -> Unit)?,
        stickyHeader: (@Composable ColumnScope.() -> Unit)?,
        sectionSpacing: Dp,
    ) {
        if (title != null || onCloseRequest != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    title?.invoke()
                }
                if (onCloseRequest != null) {
                    IconButton(
                        onClick = onCloseRequest,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource("dialog.close"),
                        )
                    }
                }
            }
        }

        if (stickyHeader != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                content = stickyHeader,
            )
        }
    }

    /**
     * A scaffold-style dialog with a scrollable content area, sticky title bar, and action bar.
     *
     * **Scroll contract** — this composable internally wraps its content in a `verticalScroll`.
     * Do **not** apply [androidx.compose.foundation.verticalScroll] or
     * [androidx.compose.foundation.horizontalScroll] directly inside the [content] lambda
     * without a bounded `heightIn` / `height` constraint.  Doing so causes an
     * [IllegalStateException] at runtime:
     * _"Vertically scrollable component was measured with an infinity maximum height constraints"_.
     *
     * ℹ️ The `NestedScrollInScrollingWrapper` Detekt rule enforces this contract automatically.
     *
     * If you need an independently scrollable sub-list inside the content, give the inner
     * container an explicit max-height constraint first:
     * ```kotlin
     * Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())
     * ```
     *
     * @param stickyHeader Optional composable rendered **between the title bar and the scrollable
     *   content area**, outside the scroll viewport.  Use it for controls that must always be
     *   visible regardless of scroll position — e.g. a search field above a long model list.
     *   Each child is automatically separated by [sectionSpacing].
     */
    @Composable
    fun scaffoldDialog(
        onDismissRequest: () -> Unit,
        actions: (@Composable RowScope.() -> Unit)? = null,
        modifier: Modifier = Modifier,
        width: Dp = 650.dp,
        maxHeightFraction: Float = 0.85f,
        properties: DialogProperties = DialogProperties(),
        shape: Shape = MaterialTheme.shapes.large,
        containerColor: Color = MaterialTheme.colorScheme.surface,
        tonalElevation: Dp = AppColors.popupSurfaceTonalElevation,
        contentPadding: Dp = dialogContentPadding,
        sectionSpacing: Dp = dialogSectionSpacing,
        onCloseRequest: (() -> Unit)? = null,
        title: @Composable (() -> Unit)? = null,
        stickyHeader: (@Composable ColumnScope.() -> Unit)? = null,
        showSectionDividers: Boolean = false,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val safeMaxHeightFraction = maxHeightFraction.coerceIn(0.35f, 1f)
        val resolvedProperties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        )
        val border = AppColors.popupBorderStroke()
        Dialog(onDismissRequest = onDismissRequest, properties = resolvedProperties) {
            MaterialTheme(colorScheme = AppColors.popupColorScheme()) {
                BoxWithConstraints {
                    Surface(
                        modifier = modifier
                            .width(width)
                            .heightIn(max = maxHeight * safeMaxHeightFraction)
                            .border(border.width, border.brush, shape),
                        shape = shape,
                        color = containerColor,
                        tonalElevation = tonalElevation,
                    ) {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = contentPadding),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            // Header/sticky-header carry their own horizontal padding so that
                            // the section dividers can bleed edge-to-edge.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = contentPadding)
                                    .padding(bottom = sectionSpacing),
                                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                            ) {
                                scaffoldDialogHeader(title, onCloseRequest, stickyHeader, sectionSpacing)
                            }

                            if (showSectionDividers) HorizontalDivider()

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = contentPadding)
                                        .padding(top = sectionSpacing, bottom = sectionSpacing)
                                        .padding(end = dialogScrollbarPadding)
                                        .verticalScroll(scrollState),
                                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                                    content = content,
                                )

                                if (scrollState.maxValue > 0) {
                                    VerticalScrollbar(
                                        adapter = rememberScrollbarAdapter(scrollState),
                                        modifier = Modifier
                                            .align(androidx.compose.ui.Alignment.CenterEnd)
                                            .fillMaxHeight(),
                                        style = scrollbarStyle(),
                                    )
                                }
                            }

                            if (showSectionDividers) HorizontalDivider()

                            if (actions != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = dialogActionBarMinHeight)
                                        .padding(horizontal = contentPadding)
                                        .padding(top = sectionSpacing),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    content = actions,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun scaffoldDialogLazyColumn(
        onDismissRequest: () -> Unit,
        actions: @Composable RowScope.() -> Unit,
        modifier: Modifier = Modifier,
        width: Dp = 650.dp,
        maxHeightFraction: Float = 0.85f,
        properties: DialogProperties = DialogProperties(),
        shape: Shape = MaterialTheme.shapes.large,
        containerColor: Color = MaterialTheme.colorScheme.surface,
        tonalElevation: Dp = AppColors.popupSurfaceTonalElevation,
        contentPadding: Dp = dialogContentPadding,
        sectionSpacing: Dp = dialogSectionSpacing,
        onCloseRequest: (() -> Unit)? = null,
        listState: LazyListState = rememberLazyListState(),
        title: @Composable (() -> Unit)? = null,
        stickyHeader: (@Composable ColumnScope.() -> Unit)? = null,
        content: LazyListScope.() -> Unit,
    ) {
        val safeMaxHeightFraction = maxHeightFraction.coerceIn(0.35f, 1f)
        val resolvedProperties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        )
        val border = AppColors.popupBorderStroke()
        Dialog(onDismissRequest = onDismissRequest, properties = resolvedProperties) {
            MaterialTheme(colorScheme = AppColors.popupColorScheme()) {
                BoxWithConstraints {
                    Surface(
                        modifier = modifier
                            .width(width)
                            .heightIn(max = maxHeight * safeMaxHeightFraction)
                            .border(border.width, border.brush, shape),
                        shape = shape,
                        color = containerColor,
                        tonalElevation = tonalElevation,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = contentPadding),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = contentPadding)
                                    .padding(bottom = sectionSpacing),
                                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                            ) {
                                scaffoldDialogHeader(title, onCloseRequest, stickyHeader, sectionSpacing)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false),
                            ) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = contentPadding)
                                        .padding(end = dialogScrollbarPadding)
                                        .padding(vertical = sectionSpacing),
                                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                                    content = content,
                                )

                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(listState),
                                    modifier = Modifier
                                        .align(androidx.compose.ui.Alignment.CenterEnd)
                                        .fillMaxHeight(),
                                    style = scrollbarStyle(),
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = dialogActionBarMinHeight)
                                    .padding(horizontal = contentPadding)
                                    .padding(top = sectionSpacing),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                content = actions,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Standardised small loading spinner for all UI contexts.
     *
     * Color defaults to [LocalContentColor] so it automatically matches its container:
     *   - inside a `primaryButton`                        → onPrimary
     *   - inside a `AppColors.cardColors(Elevation.ACCENT)` card → onPrimaryContainer
     *   - on a plain surface                               → onSurface
     *
     * Bakes in `strokeWidth = 2.dp` (lighter than M3 default of 4.dp) for inline use.
     *
     * @param size  Spinner diameter. 16.dp when inline in a button, 18.dp standalone (default).
     * @param color Override only when a dynamic/semantic color is needed (e.g. a status color).
     */
    @Composable
    fun loadingSpinner(
        modifier: Modifier = Modifier,
        size: Dp = 18.dp,
        color: Color = LocalContentColor.current,
        trackColor: Color? = null,
    ) {
        if (trackColor != null) {
            CircularProgressIndicator(
                modifier = modifier.size(size),
                strokeWidth = 2.dp,
                color = color,
                trackColor = trackColor,
            )
        } else {
            CircularProgressIndicator(
                modifier = modifier.size(size),
                strokeWidth = 2.dp,
                color = color,
            )
        }
    }

    @Composable
    fun scrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
        minimalHeight = 16.dp,
        thickness = 6.dp,
        shape = MaterialTheme.shapes.small,
        hoverDurationMillis = 300,
        unhoverColor = AppColors.surfaceColor(AppColors.Elevation.RECESSED),
        hoverColor = AppColors.tertiaryIconColor(),
    )

    /**
     * A floating popup anchored to any position via [positionProvider], using the same
     * background, border, and elevation tokens as all other popup surfaces in the app.
     *
     * Use this instead of a raw [Popup] + [Surface] whenever you need a custom-positioned
     * overlay (e.g. an upward-opening chip picker, command palette).
     */
    @Composable
    fun anchoredPopup(
        positionProvider: PopupPositionProvider,
        onDismissRequest: () -> Unit,
        modifier: Modifier = Modifier,
        shape: Shape = MaterialTheme.shapes.medium,
        properties: PopupProperties = PopupProperties(focusable = true),
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val border = AppColors.popupBorderStroke()
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismissRequest,
            properties = properties,
        ) {
            MaterialTheme(colorScheme = AppColors.popupColorScheme()) {
                Surface(
                    modifier = modifier.border(border.width, border.brush, shape),
                    shape = shape,
                    color = AppColors.popupContainerColor(),
                    tonalElevation = AppColors.popupSurfaceTonalElevation,
                    shadowElevation = AppColors.popupElevation,
                ) {
                    Column(content = content)
                }
            }
        }
    }

    /**
     * Single-line text that reveals content clipped by ellipsis/truncation when hovered,
     * by scrolling horizontally at a constant speed — the classic "marquee on hover" pattern
     * used for long list-item titles (session names, project names, tab titles, etc.).
     *
     * Text that already fits the available width never scrolls.
     *
     * @param isHovered Drives the scroll: `true` starts/holds the scroll, `false` stops it
     * immediately and the caller's own truncation (`TextOverflow.Ellipsis`) applies.
     */
    @Composable
    fun marqueeText(
        text: String,
        isHovered: Boolean,
        modifier: Modifier = Modifier,
        style: TextStyle = LocalTextStyle.current,
        color: Color = Color.Unspecified,
    ) {
        val marqueeModifier = if (isHovered) {
            Modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                animationMode = MarqueeAnimationMode.Immediately,
                velocity = 40.dp,
                initialDelayMillis = 0,
            )
        } else {
            Modifier
        }
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.then(marqueeModifier),
        )
    }
}
