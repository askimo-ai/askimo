/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Semantic text-style tokens for Askimo.
 *
 * ## Design intent
 *
 * Instead of pairing a raw `MaterialTheme.typography.*` slot with a raw
 * `MaterialTheme.colorScheme.*` color at every call site, this object provides
 * **named tokens** whose names describe *what the text is for*.
 *
 * ## Typography and color are deliberately decoupled
 *
 * Every token carries **typography only** — no fixed color. `Text`/`Icon` already
 * fall back to the ambient [androidx.compose.material3.LocalContentColor] when
 * unspecified, and every `Card`/`Surface`/`AlertDialog`/`Button` in this app sets
 * that ambient color correctly for its background (see [AppColors.cardColors],
 * [AppColors.sidebarCardColors], [AppComponents.alertDialog]). Baking a fixed color
 * into these tokens would break the moment the token is used inside a
 * differently-colored container — exactly the "tinted background, low-contrast
 * text" bug this design avoids.
 *
 * **The rule:** pick a background/content-color pair *once*, at the container
 * boundary (a `Card`, a dialog, a button). Text inside it just works — no
 * `color = ...` override needed.
 *
 * The "secondary/muted" tokens ([bodySecondary], [caption], [hint],
 * [sectionDescription], [sidebarSectionHeader], [codeSecondary],
 * [codeBlockPlaceholder], [emptyStateEmoji]) are the one place color still shows
 * up — as a partial-opacity tint of the *ambient* content color, so "more muted
 * than the primary text around it" holds true in any container.
 *
 * [errorText] is the deliberate exception: its color **is** its meaning, so it
 * stays a fixed `MaterialTheme.colorScheme.error`.
 *
 * ## Token hierarchy  (analogous to HTML headings)
 *
 * ```
 * pageTitle        ← headlineSmall  + Bold             (H1 — top of a settings page)
 * sectionTitle     ← titleMedium    + SemiBold          (H2 — named group within a page)
 * groupTitle       ← labelLarge                         (H3 — sub-group inside a section)
 * fieldLabel       ← labelMedium                        (H4 — label above a single control)
 * body             ← bodyMedium                         (primary readable text)
 * bodySecondary    ← bodyMedium     + ambient @ 70 %    (supporting / description)
 * caption          ← bodySmall      + ambient @ 70 %    (helper text below a field)
 * hint             ← labelSmall     + ambient @ 60 %    (empty-state, metadata, micro-text)
 * errorText        ← bodySmall      + error (fixed)     (validation error)
 * emptyStateEmoji  ← displayMedium  + ambient @ 70 %    (large emoji in empty screens)
 * avatarLetter     ← displayLarge                       (initial letter inside avatar circles)
 * code             ← labelSmall     + monospace         (inline path / key snippet)
 * codeSecondary    ← bodySmall      + monospace + ambient @ 70 % (stack traces / detail output)
 * codeBlock        ← 13sp           + monospace         (multi-line editor body)
 * ```
 *
 * ## Color tokens
 *
 * All relative to the ambient content color, not fixed `onSurface`/`onSurfaceVariant`:
 *
 * ```
 * primaryContent   = ambient content color        (all main text and primary icons)
 * secondaryContent = ambient content color @ 70 %  (supporting text and secondary icons)
 * disabledContent  = ambient content color @ 38 %  (disabled / ghost elements)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Text composable — use style= to apply typography; color follows the container:
 * Text("Appearance", style = AppTextStyles.pageTitle)
 * Text("Choose a theme", style = AppTextStyles.caption)
 *
 * // Icon tint — use the color token directly:
 * Icon(icon, tint = AppTextStyles.secondaryContent)
 *
 * // Only override color when semantics truly demand one independent of the
 * // container (e.g. a clickable link, or a persistent brand accent):
 * Text(name, style = AppTextStyles.body, color = MaterialTheme.colorScheme.primary)
 * ```
 *
 * ## Extending
 *
 * Add new tokens here — don't scatter raw typography+color pairs across screens.
 * Only bake in a fixed color when the color itself is the semantic meaning (like
 * [errorText]) — everything else should stay ambient.
 */
object AppTextStyles {

    // ── Semantic color tokens — relative to the ambient content color ─────────

    /**
     * Primary content color — main text and icon tints. Just the ambient content color set
     * by the nearest `Card`/`Surface`/`Dialog` (defaults to `onSurface` on a plain page).
     */
    val primaryContent: Color
        @Composable get() = LocalContentColor.current

    /**
     * Secondary content color — supporting text and icon tints. 70% of the ambient content
     * color, so it reads as "muted" relative to whatever is active in the current container.
     */
    val secondaryContent: Color
        @Composable get() = LocalContentColor.current.copy(alpha = 0.7f)

    /**
     * Disabled / ghost content color. 38% opacity follows the Material 3 disabled-state spec,
     * applied to the ambient content color rather than a fixed `onSurface`.
     */
    val disabledContent: Color
        @Composable get() = LocalContentColor.current.copy(alpha = 0.38f)

    // ── Text styles — TYPOGRAPHY ONLY (see class doc for why color isn't baked in) ──

    /**
     * Page-level heading — the top title of a settings screen or full page.
     *
     * **H1 analog.** Typography: `headlineSmall` · Weight: `Bold`
     *
     * Weight is baked in because `headlineSmall` defaults to `Regular`, too light for a
     * top-of-page heading. Bold gives clear hierarchy over [sectionTitle] (SemiBold).
     */
    val pageTitle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold,
        )

    /**
     * Section title — names a major group of controls within a page.
     *
     * **H2 analog.** Typography: `titleMedium` · Weight: `SemiBold`
     *
     * Weight is baked in because `titleMedium` defaults to `Medium`, too light for a named
     * section heading. Consistent with [pageTitle].
     */
    val sectionTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        )

    /**
     * Section description — a one-liner describing what a section does, placed directly
     * below a [sectionTitle].
     *
     * Typography: `bodySmall` · Color: ambient @ 70 %
     */
    val sectionDescription: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            color = secondaryContent,
        )

    /**
     * Group title — labels a named sub-group inside a section (e.g. "Accent color").
     *
     * **H3 analog.** Typography: `labelLarge`
     */
    val groupTitle: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge

    /**
     * Item title — the primary label of a list or card item acting as a navigation link or
     * clickable heading (e.g. a session card title).
     *
     * **H3 list-item analog.** Typography: `titleSmall` · Weight: `SemiBold`
     *
     * Override `color` at the call site for navigation links (e.g. `MaterialTheme.colorScheme.primary`).
     */
    val itemTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
        )

    /**
     * Sidebar section header — labels a collapsible group of navigation items in a
     * drawer/sidebar (e.g. "PINNED", "CONVERSATIONS").
     *
     * Deliberately smaller and more muted than the items it groups. Pair with an uppercase
     * label string for the classic "small-caps section label" look.
     *
     * Typography: `labelSmall` · Weight: `SemiBold` · Color: ambient @ 70 %
     */
    val sidebarSectionHeader: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = secondaryContent,
        )

    /**
     * Field label — shown directly above or beside a single control (slider, dropdown, text
     * field, toggle).
     *
     * **H4 analog.** Typography: `labelMedium`
     */
    val fieldLabel: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium

    /**
     * Body text — primary. Use for list-item names, selected values, and descriptive
     * sentences that are the main readable content in a view.
     *
     * Typography: `bodyMedium`
     */
    val body: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium

    /**
     * Body text — secondary. Use for supporting descriptions or subtitles below a primary
     * body line.
     *
     * Typography: `bodyMedium` · Color: ambient @ 70 %
     */
    val bodySecondary: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(
            color = secondaryContent,
        )

    /**
     * Caption / supporting text. Use for helper text below an input field, or a brief
     * explanation beneath a control group.
     *
     * Typography: `bodySmall` · Color: ambient @ 70 %
     */
    val caption: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            color = secondaryContent,
        )

    /**
     * Hint / micro text. Use for empty-state messages, metadata badges, subtle timestamps,
     * and other low-priority information.
     *
     * Typography: `labelSmall` · Color: ambient @ 60 %
     */
    val hint: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(
            color = LocalContentColor.current.copy(alpha = 0.6f),
        )

    /**
     * Error text. Use exclusively for validation errors and destructive warnings — the one
     * deliberate exception to "don't bake in color", since the color itself is the meaning.
     *
     * Typography: `bodySmall` · Color: `error` (fixed)
     */
    val errorText: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.error,
        )

    /**
     * Empty-state illustration emoji. Use for the large emoji/illustration shown in empty
     * screens and placeholder states.
     *
     * Typography: `displayMedium` · Color: ambient @ 70 %
     */
    val emptyStateEmoji: TextStyle
        @Composable get() = MaterialTheme.typography.displayMedium.copy(
            color = secondaryContent,
        )

    /**
     * Avatar initial letter. Use for the large letter shown inside avatar circles and
     * identity displays.
     *
     * Typography: `displayLarge`
     */
    val avatarLetter: TextStyle
        @Composable get() = MaterialTheme.typography.displayLarge

    // ── Code / monospace styles ───────────────────────────────────────────────

    /**
     * Secondary code / technical output text. Use for stack traces, error details, log
     * excerpts, and any multi-line technical output that is supporting rather than primary.
     *
     * Typography: `bodySmall` + current code font · Color: ambient @ 70 %
     */
    val codeSecondary: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(
            fontFamily = LocalCodeFontFamily.current,
            color = secondaryContent,
        )

    /**
     * Inline code / monospace snippet. Use for short values rendered inline: file paths,
     * API key previews, shell commands embedded in a sentence.
     *
     * Typography: `labelSmall` + current code font
     */
    val code: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(
            fontFamily = LocalCodeFontFamily.current,
        )
}
