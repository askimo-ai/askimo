/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Semantic text-style and color tokens for Askimo.
 *
 * ## Design intent
 *
 * Instead of pairing a raw `MaterialTheme.typography.*` slot with a raw
 * `MaterialTheme.colorScheme.*` color at every call site, this object provides
 * **named tokens** whose names describe *what the text is for*, not just its
 * visual appearance.  The mapping to concrete Material 3 slots is owned here
 * and applied consistently across every screen.
 *
 * ## Token hierarchy  (analogous to HTML headings + CSS color vars)
 *
 * ```
 * pageTitle        ← headlineSmall  + Bold     + onSurface       (H1 — top of a settings page)
 * sectionTitle     ← titleMedium    + onSurface       (H2 — named group within a page)
 * groupTitle       ← labelLarge     + onSurface       (H3 — sub-group inside a section)
 * fieldLabel       ← labelMedium    + onSurface       (H4 — label above a single control)
 * body             ← bodyMedium     + onSurface       (primary readable text)
 * bodySecondary    ← bodyMedium     + onSurfaceVariant (supporting / description)
 * caption          ← bodySmall      + onSurfaceVariant (helper text below a field)
 * hint             ← labelSmall     + onSurfaceVariant (empty-state, metadata, micro-text)
 * errorText        ← bodySmall      + error            (validation error)
 * emptyStateEmoji  ← displayMedium  + onSurfaceVariant (large emoji in empty screens)
 * avatarLetter     ← displayLarge   + onSurface        (initial letter inside avatar circles)
 * code             ← labelSmall     + monospace + onSurface   (inline path / key snippet)
 * codeSecondary    ← bodySmall      + monospace + onSurfaceVariant (stack traces / detail output)
 * codeBlock        ← 13sp           + monospace + onSurface   (multi-line editor body)
 * ```
 *
 * ## Color tokens
 *
 * ```
 * primaryContent   = onSurface        (all main text and primary icons)
 * secondaryContent = onSurfaceVariant (supporting text and secondary icons)
 * disabledContent  = onSurface 38 %   (disabled / ghost elements)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Text composable — use style= to apply style+color in one shot:
 * Text("Appearance", style = AppTextStyles.pageTitle)
 * Text("Choose a theme", style = AppTextStyles.caption)
 *
 * // Icon tint — use the color token directly:
 * Icon(icon, tint = AppTextStyles.secondaryContent)
 *
 * // Override color when semantics demand it (e.g. selected state):
 * Text(name, style = AppTextStyles.body, color = MaterialTheme.colorScheme.primary)
 * ```
 *
 * ## Extending
 *
 * Add new tokens here — do **not** scatter raw typography+color pairs across screens.
 * When a new visual role is needed, define it once in this object with a name
 * that describes its *purpose*, then use the token everywhere.
 */
object AppTextStyles {

    // ── Semantic color tokens ─────────────────────────────────────────────────

    /**
     * Primary content color — all main, important text and icon tints.
     * Equivalent to Material 3 `onSurface`.
     */
    val primaryContent: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    /**
     * Secondary content color — supporting, less-important text and icon tints.
     * Equivalent to Material 3 `onSurfaceVariant`.
     */
    val secondaryContent: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /**
     * Disabled / ghost content color — placeholder text and disabled controls.
     * 38 % opacity follows the Material 3 disabled-state spec.
     */
    val disabledContent: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    // ── Text styles (typography + color combined) ─────────────────────────────

    /**
     * Page-level heading — the top title of a settings screen or full page.
     *
     * **H1 analog.**
     * Typography: `headlineSmall` · Weight: `Bold` · Color: `onSurface`
     *
     * Weight is baked in because `headlineSmall` defaults to `Regular`, which reads
     * too lightly for a top-of-page heading. Bold provides clear visual hierarchy
     * over [sectionTitle] (SemiBold).
     */
    val pageTitle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

    /**
     * Section title — names a major group of controls within a page.
     *
     * **H2 analog.**
     * Typography: `titleMedium` · Weight: `SemiBold` · Color: `onSurface`
     *
     * Weight is baked in because `titleMedium` defaults to `Medium`, which reads
     * too lightly for a named section heading. Consistent with [pageTitle].
     */
    val sectionTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

    /**
     * Section description — a one-liner that describes what a section does.
     * Placed directly below a [sectionTitle].
     *
     * Typography: `bodySmall` · Color: `onSurfaceVariant`
     */
    val sectionDescription: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    /**
     * Group title — labels a named sub-group *inside* a section
     * (e.g. "Accent color", "Font family").
     *
     * **H3 analog.**
     * Typography: `labelLarge` · Color: `onSurface`
     */
    val groupTitle: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )

    /**
     * Item title — the primary label of a list or card item that acts as a
     * navigation link or clickable heading (e.g. a session card title).
     *
     * **H3 list-item analog.**
     * Typography: `titleSmall` · Weight: `SemiBold` · Color: `onSurface`
     *
     * Override `color` at the call site when the item is a navigation link
     * (e.g. `color = MaterialTheme.colorScheme.primary`).
     */
    val itemTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

    /**
     * Field label — the label shown directly above or beside a single control
     * (slider, dropdown, text field, toggle).
     *
     * **H4 analog.**
     * Typography: `labelMedium` · Color: `onSurface`
     */
    val fieldLabel: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )

    /**
     * Body text — primary.
     * Use for list-item names, selected values, and descriptive sentences
     * that are the main readable content in a view.
     *
     * Typography: `bodyMedium` · Color: `onSurface`
     */
    val body: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )

    /**
     * Body text — secondary.
     * Use for supporting descriptions or subtitles below a primary body line.
     *
     * Typography: `bodyMedium` · Color: `onSurfaceVariant`
     */
    val bodySecondary: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    /**
     * Caption / supporting text.
     * Use for helper text below an input field, or a brief explanation
     * beneath a control group.
     *
     * Typography: `bodySmall` · Color: `onSurfaceVariant`
     */
    val caption: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    /**
     * Hint / micro text.
     * Use for empty-state messages, metadata badges, subtle timestamps,
     * and other low-priority information.
     *
     * Typography: `labelSmall` · Color: `onSurfaceVariant`
     */
    val hint: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    /**
     * Error text.
     * Use exclusively for validation errors and destructive warnings.
     *
     * Typography: `bodySmall` · Color: `error`
     */
    val errorText: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.error,
        )

    /**
     * Empty-state illustration emoji.
     * Use for the large emoji/illustration shown in empty screens and placeholder states.
     *
     * Typography: `displayMedium` · Color: `onSurfaceVariant`
     */
    val emptyStateEmoji: TextStyle
        @Composable get() = MaterialTheme.typography.displayMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    /**
     * Avatar initial letter.
     * Use for the large letter shown inside avatar circles and identity displays.
     *
     * Typography: `displayLarge` · Color: `onSurface`
     *
     * Override `color` when the avatar sits on a colored container
     * (e.g. `color = MaterialTheme.colorScheme.onPrimaryContainer`).
     */
    val avatarLetter: TextStyle
        @Composable get() = MaterialTheme.typography.displayLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )

    // ── Code / monospace styles ───────────────────────────────────────────────

    /**
     * Secondary code / technical output text.
     * Use for stack traces, error details, log excerpts, and any multi-line
     * technical output that is supporting rather than primary content.
     *
     * Typography: `bodySmall` + current code font · Color: `onSurfaceVariant`
     */
    val codeSecondary: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            fontFamily = LocalCodeFontFamily.current,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    /**
     * Inline code / monospace snippet.
     * Use for short values rendered inline: file paths, API key previews,
     * shell commands embedded in a sentence.
     *
     * Typography: `labelSmall` + current code font · Color: `onSurface`
     */
    val code: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(
            fontFamily = LocalCodeFontFamily.current,
            color = MaterialTheme.colorScheme.onSurface,
        )

    /**
     * Code-block editor text.
     * Use for multi-line text editors displaying source code, prompts,
     * or raw file content (skill editor, file editor, etc.).
     *
     * Fixed at 13 sp / 21 sp line-height to ensure a comfortable mono reading
     * experience independent of the user's UI font-size preference.
     * Color: `onSurface`
     */
    val codeBlock: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalCodeFontFamily.current,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

    /**
     * Code-block placeholder text.
     * Shown inside an empty [codeBlock] editor to indicate what the user
     * should type.  Same metrics as [codeBlock] but using [secondaryContent].
     */
    val codeBlockPlaceholder: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalCodeFontFamily.current,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}
