/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.askimo.ui.common.i18n.stringResource

object AppComponents {

    // Shared spacing/tokens for scaffold-style dialogs.
    val dialogContentPadding: Dp = 24.dp
    val dialogSectionSpacing: Dp = 16.dp
    val dialogActionBarMinHeight: Dp = 56.dp
    val dialogScrollbarPadding: Dp = 12.dp

    // ── Navigation ───────────────────────────────────────────────────────────

    @Composable
    fun navigationDrawerItemColors(): NavigationDrawerItemColors = NavigationDrawerItemDefaults.colors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedBadgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
        unselectedContainerColor = Color.Transparent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedBadgeColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    @Composable
    fun navigationRailItemColors(): NavigationRailItemColors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    )

    // ── Cards ─────────────────────────────────────────────────────────────────

    /**
     * A card with standardized hover behavior across all themes.
     *
     * Bakes in:
     * - `.clip(shape)` **before** `hoverable`/`clickable` so the ripple and hover
     *   highlight are always clipped to rounded corners (prevents the rectangle-border bug).
     * - Default: `surfaceVariant.copy(alpha = 0.5f)` + `outlineVariant.copy(alpha = 0.6f)` border.
     * - Hover:   `primaryContainer.copy(alpha = 0.25f)` + `primary.copy(alpha = 0.4f)` border.
     *
     * Use this instead of bare [Card] whenever the card is clickable.
     */
    @Composable
    fun clickableCard(
        onClick: (() -> Unit)?,
        modifier: Modifier = Modifier,
        shape: Shape = MaterialTheme.shapes.medium,
        colors: CardColors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isHovered by interactionSource.collectIsHoveredAsState()

        val resolvedColors = if (onClick != null && isHovered) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            colors
        }

        val border = BorderStroke(
            1.dp,
            if (onClick != null && isHovered) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
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

    @Composable
    fun primaryCardColors(): CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    @Composable
    fun bannerCardColors(): CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )

    @Composable
    fun secondaryCardColors(): CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )

    @Composable
    fun surfaceVariantCardColors(): CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // ── Buttons & Icons ───────────────────────────────────────────────────────

    @Composable
    fun primaryIconButtonColors(): IconButtonColors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

    @Composable
    fun primaryTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
    )

    // ── Inputs ────────────────────────────────────────────────────────────────

    @Composable
    fun outlinedTextFieldColors(
        focusedBorderColor: Color = MaterialTheme.colorScheme.onSurface,
        unfocusedBorderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
        focusedLabelColor: Color = MaterialTheme.colorScheme.onSurface,
        unfocusedLabelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedTextColor: Color = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor: Color = MaterialTheme.colorScheme.onSurface,
        cursorColor: Color = MaterialTheme.colorScheme.onSurface,
    ): TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = focusedBorderColor,
        unfocusedBorderColor = unfocusedBorderColor,
        focusedLabelColor = focusedLabelColor,
        unfocusedLabelColor = unfocusedLabelColor,
        focusedTextColor = focusedTextColor,
        unfocusedTextColor = unfocusedTextColor,
        cursorColor = cursorColor,
    )

    /**
     * Themed [OutlinedTextField] with default colors from [outlinedTextFieldColors].
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
            colors = outlinedTextFieldColors(),
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
            colors = outlinedTextFieldColors(),
        )
    }

    @Composable
    fun menuItemColors(): MenuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = MaterialTheme.colorScheme.onSurface,
        trailingIconColor = MaterialTheme.colorScheme.onSurface,
        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

    // ── Surface Colors ────────────────────────────────────────────────────────

    @Composable
    fun sidebarSurfaceColor(): Color {
        val surfaceColor = MaterialTheme.colorScheme.surface
        val primaryColor = MaterialTheme.colorScheme.primary
        val isLight = surfaceColor.luminance() > 0.5
        val tintAmount = if (isLight) 0.08f else 0.12f
        val base = Color(
            red = surfaceColor.red + (primaryColor.red - surfaceColor.red) * tintAmount,
            green = surfaceColor.green + (primaryColor.green - surfaceColor.green) * tintAmount,
            blue = surfaceColor.blue + (primaryColor.blue - surfaceColor.blue) * tintAmount,
            alpha = surfaceColor.alpha,
        )
        // When a background image is active, let it show through the sidebar
        return if (LocalBackgroundActive.current) base.copy(alpha = 0.82f) else base
    }

    @Composable
    fun sidebarHeaderColor(): Color {
        val base = MaterialTheme.colorScheme.secondaryContainer
        return if (LocalBackgroundActive.current) base.copy(alpha = 0.82f) else base
    }

    @Composable
    fun userMessageBackground(): Color {
        val surface = MaterialTheme.colorScheme.surface
        val isLight = surface.luminance() > 0.5
        return if (isLight) {
            Color(
                red = (surface.red * 0.92f).coerceIn(0f, 1f),
                green = (surface.green * 0.92f).coerceIn(0f, 1f),
                blue = (surface.blue * 0.92f).coerceIn(0f, 1f),
                alpha = surface.alpha,
            )
        } else {
            Color(
                red = (surface.red + 0.10f).coerceIn(0f, 1f),
                green = (surface.green + 0.10f).coerceIn(0f, 1f),
                blue = (surface.blue + 0.10f).coerceIn(0f, 1f),
                alpha = surface.alpha,
            )
        }
    }

    @Composable
    fun userMessageContentColor(): Color = MaterialTheme.colorScheme.onSurface

    @Composable
    fun codeBlockBackground(): Color {
        val surface = MaterialTheme.colorScheme.surface
        val isLight = surface.luminance() > 0.5
        return if (isLight) {
            Color(
                red = (surface.red * 0.85f).coerceIn(0f, 1f),
                green = (surface.green * 0.85f).coerceIn(0f, 1f),
                blue = (surface.blue * 0.85f).coerceIn(0f, 1f),
                alpha = surface.alpha,
            )
        } else {
            Color(
                red = (surface.red * 0.75f).coerceIn(0f, 1f),
                green = (surface.green * 0.75f).coerceIn(0f, 1f),
                blue = (surface.blue * 0.75f).coerceIn(0f, 1f),
                alpha = surface.alpha,
            )
        }
    }

    @Composable
    fun codeBlockContentColor(): Color = MaterialTheme.colorScheme.onSurface

    @Composable
    fun codeBlockBorderColor(): Color = MaterialTheme.colorScheme.outlineVariant

    @Composable
    fun isCodeBlockDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5

    // ── Icon & Text Tints ─────────────────────────────────────────────────────

    @Composable
    fun secondaryIconColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    @Composable
    fun tertiaryIconColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    @Composable
    fun secondaryTextColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    @Composable
    fun dropdownMenu(
        expanded: Boolean,
        onDismissRequest: () -> Unit,
        modifier: Modifier = Modifier,
        offset: DpOffset = DpOffset.Zero,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surfaceContainer = MaterialTheme.colorScheme.surface,
            ),
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismissRequest,
                offset = offset,
                modifier = modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
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
        tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
        properties: DialogProperties = DialogProperties(),
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surfaceContainerHigh = MaterialTheme.colorScheme.surface,
            ),
        ) {
            AlertDialog(
                onDismissRequest = onDismissRequest,
                confirmButton = confirmButton,
                modifier = modifier,
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

    @Composable
    fun scaffoldDialog(
        onDismissRequest: () -> Unit,
        actions: @Composable RowScope.() -> Unit,
        modifier: Modifier = Modifier,
        width: Dp = 650.dp,
        maxHeightFraction: Float = 0.85f,
        properties: DialogProperties = DialogProperties(),
        shape: Shape = MaterialTheme.shapes.large,
        containerColor: Color = MaterialTheme.colorScheme.surface,
        tonalElevation: Dp = 8.dp,
        contentPadding: Dp = dialogContentPadding,
        sectionSpacing: Dp = dialogSectionSpacing,
        onCloseRequest: (() -> Unit)? = null,
        title: (@Composable () -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val safeMaxHeightFraction = maxHeightFraction.coerceIn(0.35f, 1f)
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            BoxWithConstraints {
                Surface(
                    modifier = modifier
                        .width(width)
                        .heightIn(max = maxHeight * safeMaxHeightFraction),
                    shape = shape,
                    color = containerColor,
                    tonalElevation = tonalElevation,
                ) {
                    val scrollState = rememberScrollState()
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
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

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        ) {
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier
                                    .fillMaxWidth()
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = dialogActionBarMinHeight),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            content = actions,
                        )
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
        tonalElevation: Dp = 8.dp,
        contentPadding: Dp = dialogContentPadding,
        sectionSpacing: Dp = dialogSectionSpacing,
        onCloseRequest: (() -> Unit)? = null,
        listState: LazyListState = rememberLazyListState(),
        title: (@Composable () -> Unit)? = null,
        content: LazyListScope.() -> Unit,
    ) {
        val safeMaxHeightFraction = maxHeightFraction.coerceIn(0.35f, 1f)
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            BoxWithConstraints {
                Surface(
                    modifier = modifier
                        .width(width)
                        .heightIn(max = maxHeight * safeMaxHeightFraction),
                    shape = shape,
                    color = containerColor,
                    tonalElevation = tonalElevation,
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
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

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = dialogScrollbarPadding),
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
                                .heightIn(min = dialogActionBarMinHeight),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            content = actions,
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun scrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
        minimalHeight = 16.dp,
        thickness = 6.dp,
        shape = MaterialTheme.shapes.small,
        hoverDurationMillis = 300,
        unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
    )
}
