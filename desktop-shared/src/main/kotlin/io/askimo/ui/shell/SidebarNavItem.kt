/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Descriptor for a primary navigation item in the sidebar.
 *
 * Callers build an ordered [List] of these and pass it to [navigationSidebar].
 * The sidebar renders them in list order, giving callers full control over
 * which items appear and in what sequence without touching shared code.
 *
 * @param id         Stable key — used as a [androidx.compose.runtime.remember] key for hover state.
 * @param labelRes   i18n resource key for the item label and collapsed tooltip.
 * @param icon       Material icon vector.
 * @param isSelected Whether this item is currently active/highlighted.
 * @param isVisible  When false the item is omitted entirely from the sidebar.
 * @param onClick    Navigation action invoked when the item is clicked.
 * @param badge      Optional trailing composable rendered inside the item's badge slot
 *                   (expanded sidebar only). Receives [isHovered] so callers can show
 *                   hover-only actions (e.g. a "+" button on the Projects entry).
 */
data class SidebarNavItem(
    val id: String,
    val labelRes: String,
    val icon: ImageVector,
    val isSelected: Boolean,
    val isVisible: Boolean = true,
    val onClick: () -> Unit,
    val badge: (@Composable (isHovered: Boolean) -> Unit)? = null,
)
