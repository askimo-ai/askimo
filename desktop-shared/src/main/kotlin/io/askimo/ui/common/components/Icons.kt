/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// Base
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Base icon with an explicit tint. Prefer the semantic variants below
 * so that tint choices stay consistent across all 8 themes.
 */
@Composable
fun appIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Primary / active icons
// Uses onSurface — NEVER primary (near-black in dark theme → invisible)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Active / selected-state icon.
 * Examples: active sort arrow, history collapse/expand, logo tint.
 */
@Composable
fun primaryIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Secondary / muted icons
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inactive or decorative icon.
 * Examples: sidebar nav icons, leading icons in menu items, folder/category icons.
 */
@Composable
fun secondaryIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Dimmed/ghost icon for placeholder or deeply secondary UI.
 * Uses onSurface at 40% opacity.
 */
@Composable
fun mutedIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        modifier = modifier,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Status icons
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Success / completion icon (CheckCircle).
 * Uses onSurface — safe across all 8 themes including dark mode.
 *
 * @param size 24.dp standard; use 32/64.dp for hero placement in dialogs.
 */
@Composable
fun successIcon(
    contentDescription: String? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(size),
    )
}

/**
 * Error icon. Uses [MaterialTheme.colorScheme.error].
 */
@Composable
fun errorIcon(
    contentDescription: String? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Error,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier.size(size),
    )
}

/**
 * Warning icon. Uses [MaterialTheme.colorScheme.error] at 80% opacity.
 */
@Composable
fun warningIcon(
    contentDescription: String? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
        modifier = modifier.size(size),
    )
}

/**
 * Info icon. Uses [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun infoIcon(
    contentDescription: String? = null,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Info,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Action icons  (menus, toolbars, context actions)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Delete / remove action. Uses [MaterialTheme.colorScheme.error].
 */
@Composable
fun deleteIcon(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Delete,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/**
 * Edit / rename action. Uses [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun editIcon(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Edit,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Share / export action. Uses [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun shareIcon(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Share,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Copy action. Switches between copy and checkmark when [copied] is true.
 * Uses [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun copyIcon(
    copied: Boolean = false,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Overflow / more-options (⋮). Uses [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun moreOptionsIcon(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.MoreVert,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Close / dismiss. Uses [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun closeIcon(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Close,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Domain-specific icons
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Star / pin icon for starred/pinned items in menus and lists.
 * Uses onSurfaceVariant — NOT primary (invisible in dark theme).
 */
@Composable
fun starIcon(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.Star,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Folder / project icon. Uses [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun folderIcon(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Default.FolderOpen,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Chat / session icon. Uses [MaterialTheme.colorScheme.onSurfaceVariant].
 */
@Composable
fun chatIcon(
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.Chat,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
