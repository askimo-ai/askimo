/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

object AppComponents {

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
     * A card with standardised hover behaviour across all themes.
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
    fun outlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.onSurface,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedLabelColor = MaterialTheme.colorScheme.onSurface,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = MaterialTheme.colorScheme.onSurface,
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
}
