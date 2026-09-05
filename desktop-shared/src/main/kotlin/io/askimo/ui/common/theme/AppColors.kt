/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Every color token in Askimo — the single place to look when changing "what color is X".
 *
 * ## Component Catalog — "I'm building X, which function do I call?"
 *
 * Look up the UI element you're building here *first*, before reaching for a raw
 * `MaterialTheme.colorScheme.*` or writing a new `.copy(alpha = ...)`. If your element
 * genuinely isn't listed, that's a signal to add a new named token (with a doc comment
 * explaining *why*) rather than hand-rolling a color at the call site.
 *
 * | UI element                                             | Function(s) |
 * |----------------------------------------------------------|-------------|
 * | Sidebar background                                        | [sidebarSurfaceColor] |
 * | Sidebar header row (logo/title bar)                       | [sidebarHeaderColor] + [sidebarHeaderContentColor] |
 * | Settings/search-result card, static pill, table header    | [cardColors] / [surfaceColor] with [Elevation.RAISED] |
 * | Row hover highlight (non-selection)                       | [surfaceColor] with [Elevation.RAISED] |
 * | Selected list/tree row                                    | [surfaceColor] with [Elevation.SELECTED] |
 * | Inline rename field, dropdown pill on an input            | [surfaceColor] with [Elevation.EMPHASIS] |
 * | Headline banner / deliberate call-to-action card          | [cardColors] with [Elevation.ACCENT] |
 * | Recessed panel (viewer, collapsed rail)                   | [surfaceColor] with [Elevation.RECESSED] |
 * | Dialog / dropdown / popup surface                         | [popupContainerColor], [popupBorderStroke], [popupColorScheme] |
 * | Full-screen modal scrim                                   | [scrimColor] |
 * | Badge distinguishing a category/variant (e.g. built-in vs custom) | [variantBadgeContainerColor] / [variantBadgeContentColor] with [BadgeTone] |
 * | Small numeric count badge (e.g. bookmark count)           | [countBadgeAccentColor] |
 * | Disabled text/icon                                        | [disabledContentColor] |
 * | Delete/remove icon button                                 | [destructiveIconColor] |
 * | Warning icon/accent (non-destructive)                     | [warningColor] |
 * | Draggable resize handle/divider, hovered                  | [resizeHandleHoverColor] |
 * | Tool-call/agent-step status dot or icon                   | [statusAccentColor] / [statusIconColor] with [StatusTone] |
 * | Outlined text field                                       | [outlinedTextFieldColors] |
 * | Dropdown/context menu item                                | [menuItemColors] |
 * | Chat message bubble background                            | [userMessageBackground] / [outdatedUserMessageBackground] |
 * | Outdated message full-bubble scrim                        | [outdatedMessageOverlayColor] |
 * | Background-image / thumbnail picker tile scrim            | [imageThumbnailScrimColor] |
 * | Code block background                                     | [codeBlockBackground] / [codeBlockContentColor] |
 * | Secondary/tertiary icon or text (non-interactive)         | [secondaryIconColor] / [tertiaryIconColor] |
 * | Inline bar-chart track / value label (pass the bar's own series color) | [chartBarTrackColor] / [chartValueLabelColor] |
 * | Loading-skeleton placeholder box                          | [skeletonPlaceholderColor] |
 * | Text/badge tint derived from a card's own contentColor (pass the card's `contentColor`) | [cardBadgeContainerColor] / [cardSecondaryContentColor] / [cardMonospaceDetailColor] |
 */
object AppColors {

    // ── Elevation Scale ────────────────────────────────────────────────────────
    // The single source of truth for every "surface sitting above its background"
    // in the app: cards, pills, rows, hover/selection states.

    enum class Elevation {
        /** Recedes into the background — content viewers, expanded "raw output" panels,
         *  a collapsed side rail. */
        RECESSED,

        /** The standard "card/pill/row at rest" background — settings option cards,
         *  search-result cards, static info pills, table header rows. Also the right
         *  choice for a plain (non-selection) hover highlight on a list row, since the
         *  same subtle lift reads correctly in both cases. */
        RAISED,

        /** A stronger lift for elements that must stand out even against an already
         *  [RAISED] container — an inline rename field sitting on a raised tree row, a
         *  dropdown/selector pill sitting on an input field, a callout box. */
        EMPHASIS,

        /** True selection/active state — the one tier that shifts hue (toward
         *  [MaterialTheme.colorScheme.primaryContainer]) rather than just opacity, so a
         *  selected item is never confused with a merely-hovered or merely-raised one. */
        SELECTED,

        /** Full-strength accent container — a deliberate call-to-action or headline banner.
         *  Rare; prefer [RAISED]/[EMPHASIS] unless the surface truly needs to read as
         *  "primary." */
        ACCENT,
    }

    /** Background [Color] for [tier] — the one place every elevation color is defined. */
    @Composable
    fun surfaceColor(tier: Elevation): Color = when (tier) {
        Elevation.RECESSED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        Elevation.RAISED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        Elevation.EMPHASIS -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        Elevation.SELECTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        Elevation.ACCENT -> MaterialTheme.colorScheme.primaryContainer
    }

    /** Matching content (text/icon) [Color] for [tier] — always paired with [surfaceColor]. */
    @Composable
    fun contentColorFor(tier: Elevation): Color = when (tier) {
        Elevation.SELECTED, Elevation.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    /**
     * Muted secondary-text variant of [contentColorFor] — preserves a primary/secondary text
     * hierarchy even when sitting on a colored [Elevation.SELECTED]/[Elevation.ACCENT]
     * container (e.g. the hex code shown under a selected accent-color preset's label).
     */
    @Composable
    fun secondaryContentColorFor(tier: Elevation): Color = contentColorFor(tier).copy(alpha = 0.82f)

    /** [CardColors] for [tier] — for any `Card` that isn't `AppComponents.clickableCard`. */
    @Composable
    fun cardColors(tier: Elevation): CardColors = CardDefaults.cardColors(
        containerColor = surfaceColor(tier),
        contentColor = contentColorFor(tier),
    )

    // ── Popup / Overlay Surface tokens ────────────────────────────────────────
    // Single source of truth for every floating surface: dialogs, dropdowns,
    // notification panels, popup cards.  Centralised here so the visual language
    // of all overlays can be changed in one place.

    /** Background color for all floating surfaces (dialogs, popups, dropdowns). */
    @Composable
    fun popupContainerColor(): Color = MaterialTheme.colorScheme.surface

    /**
     * Dimming overlay tint for scrims — full-screen modal backdrops (dialog-over-content),
     * and small always-dark badges that need contrast against arbitrary photo content
     * (e.g. an icon button overlaid on an image thumbnail).
     *
     * Built on [MaterialTheme.colorScheme.scrim], which — unlike `onSurface`/`onSurfaceVariant` —
     * is specified to stay black-ish in both light and dark themes, so it's the only token-safe
     * base for an overlay that must always read as "dark" regardless of the active theme.
     *
     * @param alpha Strength of the dim — heavier for full-screen backdrops (~0.3-0.35),
     *   lighter or heavier for small icon badges depending on desired contrast (~0.4-0.5).
     */
    @Composable
    fun scrimColor(alpha: Float): Color = MaterialTheme.colorScheme.scrim.copy(alpha = alpha)

    /**
     * Consistent 1 dp border for all floating surfaces.
     * Provides subtle depth definition without relying on elevation alone, and
     * ensures popups read clearly against any window background.
     */
    @Composable
    fun popupBorderStroke(): BorderStroke = BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )

    /**
     * Drop-shadow elevation for floating Card surfaces (e.g. notification popup).
     * Controls visible shadow only — kept separate from [popupSurfaceTonalElevation]
     * so shadow depth and tonal colour are independently adjustable.
     */
    val popupElevation: Dp = 8.dp

    /**
     * Tonal elevation for dialog/popup Surfaces — intentionally **0.dp**.
     *
     * M3 tonal elevation blends a primary-colour overlay into the Surface
     * background, making a scaffold dialog / alert dialog appear slightly different
     * from a dropdown menu (which forces all surfaceContainer tonal slots to plain
     * surface via [popupColorScheme]). Keeping this at zero ensures every popup
     * background resolves to the same pure [popupContainerColor] regardless of the
     * active theme seed colour.
     */
    val popupSurfaceTonalElevation: Dp = 0.dp

    /**
     * Shared MaterialTheme colorScheme override for all popup surfaces.
     *
     * Forces every M3 surfaceContainer tonal slot to [popupContainerColor] so
     * that Material3 components rendered inside (DropdownMenu, AlertDialog, etc.)
     * pick up the canonical popup background rather than their own tonal slot.
     * Combine with [popupSurfaceTonalElevation] = 0 for a fully consistent look.
     */
    @Composable
    fun popupColorScheme() = MaterialTheme.colorScheme.let { cs ->
        val bg = popupContainerColor()
        cs.copy(
            surfaceContainerLowest = bg,
            surfaceContainerLow = bg,
            surfaceContainer = bg,
            surfaceContainerHigh = bg,
            surfaceContainerHighest = bg,
        )
    }

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

    // ── Buttons & Icons ───────────────────────────────────────────────────────

    /**
     * Standard M3 disabled-content opacity (38%) applied to [MaterialTheme.colorScheme.onSurface] —
     * the single source for every "disabled text/icon" tint in the app (icon buttons, menu items,
     * etc), instead of each color-set repeating the same `.copy(alpha = 0.38f)` literal.
     */
    @Composable
    fun disabledContentColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    @Composable
    fun primaryIconButtonColors(): IconButtonColors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = disabledContentColor(),
    )

    @Composable
    fun primaryTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
    )

    /**
     * Closed set of badge "variants" for MCP server tool-count badges — a built-in server
     * reads as a distinct hue from a custom/user-added one. Kept as an enum (like
     * [StatusTone]) rather than accepting an arbitrary [Color] parameter, so every badge in
     * the app draws from the same fixed, curated palette instead of each call site inventing
     * its own hue.
     */
    enum class BadgeTone {
        /** Built-in / first-party integration. */
        BUILT_IN,

        /** Custom / user-added integration. */
        CUSTOM,
    }

    /**
     * Background tint for a small colored badge/chip that distinguishes a category or
     * variant (e.g. built-in vs custom), scaled by [contentAlpha] so a disabled badge fades
     * along with the rest of its row instead of fighting for attention. Named distinctly
     * from [countBadgeAccentColor] — this one answers "which variant is this," not "how many."
     */
    @Composable
    fun variantBadgeContainerColor(tone: BadgeTone, contentAlpha: Float = 1f): Color {
        val hue = when (tone) {
            BadgeTone.BUILT_IN -> MaterialTheme.colorScheme.tertiary
            BadgeTone.CUSTOM -> MaterialTheme.colorScheme.primary
        }
        return hue.copy(alpha = 0.2f * contentAlpha)
    }

    /**
     * Content tint paired with [variantBadgeContainerColor]. [isEnabled] additionally mutes a
     * [BadgeTone.CUSTOM] badge to [tertiaryIconColor] when its row is disabled, matching the
     * existing MCP-server-row disabled treatment.
     */
    @Composable
    fun variantBadgeContentColor(tone: BadgeTone, contentAlpha: Float = 1f, isEnabled: Boolean = true): Color = when (tone) {
        BadgeTone.BUILT_IN -> MaterialTheme.colorScheme.tertiary.copy(alpha = contentAlpha)
        BadgeTone.CUSTOM -> if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else tertiaryIconColor()
    }

    /**
     * Accent tint for a small numeric count badge (e.g. the bookmark-count badge on a
     * sidebar session row) — primary at reduced strength so it reads as a subtle inline
     * indicator rather than a primary-colored call to action. Unlike [variantBadgeContentColor],
     * this isn't a "which variant" choice — every count badge in the app uses this same tint.
     */
    @Composable
    fun countBadgeAccentColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)

    /**
     * Track color for [AppComponents.loadingSpinner] when it sits on top of a filled/primary
     * button — needs to be a translucent [MaterialTheme.colorScheme.onPrimary] rather than the
     * spinner's usual neutral track, since a neutral track wouldn't read against a primary fill.
     */
    @Composable
    fun onPrimaryTrackColor(): Color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f)

    /**
     * Warning tint — [MaterialTheme.colorScheme.error] at reduced opacity. Used for
     * non-destructive but important alerts/status (warning icons, "failed" accents) that
     * should read as visually distinct without being as alarming as a full-strength error.
     */
    @Composable
    fun warningColor(): Color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)

    /**
     * Muted destructive-action tint — [MaterialTheme.colorScheme.error] at 70% opacity. The
     * standard tint for delete/remove icon buttons across the app (history rows, file panels,
     * etc) — slightly softer than [warningColor] since it's an available action rather than
     * a status/warning that must stay maximally legible.
     */
    @Composable
    fun destructiveIconColor(): Color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)

    /**
     * Accent tint for a draggable resize handle/divider while hovered — signals "this is
     * interactive" without a full-strength primary fill. Pair with [codeBlockBorderColor]
     * (or [MaterialTheme.colorScheme.outlineVariant]) for the at-rest state.
     */
    @Composable
    fun resizeHandleHoverColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)

    // ── Status Tones ───────────────────────────────────────────────────────────
    // Shared "running / succeeded / failed" outcome coloring for any inline status
    // indicator — accent bars, dots, and icon tints (tool-call rows, agent run steps,
    // background job chips, etc). Centralised so every "is this still going / did it
    // work / did it fail" signal in the app uses the same three colors.

    enum class StatusTone {
        /** In progress, or no outcome yet — reads as inert/neutral, not colored. */
        NEUTRAL,

        /** Completed successfully. */
        SUCCESS,

        /** Failed / errored. */
        FAILURE,
    }

    /** Color for a status accent bar / dot (e.g. the left accent bar on a tool-call row). */
    @Composable
    fun statusAccentColor(tone: StatusTone): Color = when (tone) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.outlineVariant
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        StatusTone.FAILURE -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    }

    /** Color for a status icon/label — stronger than [statusAccentColor] since icons/text
     *  need to read clearly rather than just hint at the outcome. */
    @Composable
    fun statusIconColor(tone: StatusTone): Color = when (tone) {
        StatusTone.NEUTRAL -> secondaryIconColor()
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        StatusTone.FAILURE -> warningColor()
    }

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
        containerColor: Color = MaterialTheme.colorScheme.surface,
    ): TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = focusedBorderColor,
        unfocusedBorderColor = unfocusedBorderColor,
        focusedLabelColor = focusedLabelColor,
        unfocusedLabelColor = unfocusedLabelColor,
        focusedTextColor = focusedTextColor,
        unfocusedTextColor = unfocusedTextColor,
        cursorColor = cursorColor,
        focusedContainerColor = containerColor,
        unfocusedContainerColor = containerColor,
        disabledContainerColor = containerColor,
    )

    @Composable
    fun menuItemColors(): MenuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = MaterialTheme.colorScheme.onSurface,
        trailingIconColor = MaterialTheme.colorScheme.onSurface,
        disabledTextColor = disabledContentColor(),
        disabledLeadingIconColor = disabledContentColor(),
        disabledTrailingIconColor = disabledContentColor(),
    )

    // ── Sidebar Colors ────────────────────────────────────────────────────────

    @Composable
    private fun sidebarTint(headerStrength: Boolean): Color {
        val surfaceColor = MaterialTheme.colorScheme.surface
        val primaryColor = MaterialTheme.colorScheme.primary
        val isLight = surfaceColor.luminance() > 0.5
        val tintAmount = when {
            headerStrength && isLight -> 0.12f
            headerStrength -> 0.16f
            isLight -> 0.08f
            else -> 0.12f
        }
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
    fun sidebarSurfaceColor(): Color = sidebarTint(headerStrength = false)

    @Composable
    fun sidebarHeaderColor(): Color = sidebarTint(headerStrength = true)

    /**
     * Contrast-safe text/icon color for content painted directly on [sidebarHeaderColor] —
     * e.g. the logo, app title, and collapse/expand icon in the sidebar header row.
     */
    @Composable
    fun sidebarHeaderContentColor(): Color {
        val backgroundLuminance = sidebarHeaderColor().luminance()
        val onSurface = MaterialTheme.colorScheme.onSurface
        val inverseOnSurface = MaterialTheme.colorScheme.inverseOnSurface
        return if (backgroundLuminance > 0.5) {
            if (onSurface.luminance() < inverseOnSurface.luminance()) onSurface else inverseOnSurface
        } else {
            if (onSurface.luminance() > inverseOnSurface.luminance()) onSurface else inverseOnSurface
        }
    }

    // ── Chat / Content Shading ────────────────────────────────────────────────

    /**
     * Shifts [MaterialTheme.colorScheme.surface] toward black/white by [amount] — the one
     * primitive behind every "surface, but slightly shaded" background in the app
     * ([userMessageBackground], [codeBlockBackground]).
     */
    @Composable
    private fun shadedSurface(amount: Float): Color {
        val surface = MaterialTheme.colorScheme.surface
        val isLight = surface.luminance() > 0.5
        return if (isLight) {
            Color(
                red = (surface.red * (1f - amount)).coerceIn(0f, 1f),
                green = (surface.green * (1f - amount)).coerceIn(0f, 1f),
                blue = (surface.blue * (1f - amount)).coerceIn(0f, 1f),
                alpha = surface.alpha,
            )
        } else {
            Color(
                red = (surface.red + amount).coerceIn(0f, 1f),
                green = (surface.green + amount).coerceIn(0f, 1f),
                blue = (surface.blue + amount).coerceIn(0f, 1f),
                alpha = surface.alpha,
            )
        }
    }

    /** Subtle shade — message bubbles. */
    @Composable
    fun userMessageBackground(): Color = shadedSurface(if (MaterialTheme.colorScheme.surface.luminance() > 0.5) 0.08f else 0.10f)

    /** Faded variant of [userMessageBackground] — outdated/superseded messages that are still
     *  visible for context but shouldn't read as fully "current". */
    @Composable
    fun outdatedUserMessageBackground(): Color = userMessageBackground().copy(alpha = 0.5f)

    /**
     * Translucent scrim painted over a full outdated message bubble (any role) to mute it —
     * pairs with [outdatedUserMessageBackground] but applies to the whole bubble (including
     * AI messages) rather than just the user-bubble shade.
     */
    @Composable
    fun outdatedMessageOverlayColor(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)

    /**
     * Dim scrim painted over a background/thumbnail image tile so overlaid label text stays
     * readable regardless of the image's own colors (e.g. the background-image picker tiles
     * in Appearance settings).
     */
    @Composable
    fun imageThumbnailScrimColor(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)

    @Composable
    fun userMessageContentColor(): Color = MaterialTheme.colorScheme.onSurface

    /** Stronger shade than [userMessageBackground] — code blocks need to read as clearly
     *  distinct from surrounding prose. */
    @Composable
    fun codeBlockBackground(): Color = shadedSurface(if (MaterialTheme.colorScheme.surface.luminance() > 0.5) 0.15f else 0.25f)

    @Composable
    fun codeBlockContentColor(): Color = MaterialTheme.colorScheme.onSurface

    @Composable
    fun codeBlockBorderColor(): Color = MaterialTheme.colorScheme.outlineVariant

    @Composable
    fun isCodeBlockDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5

    // ── Icon & Text Tints ─────────────────────────────────────────────────────
    // Prefer AppTextStyles.primaryContent / secondaryContent over these helpers.
    // These are kept for call sites that haven't been migrated yet.

    /** @see AppTextStyles.secondaryContent */
    @Composable
    fun secondaryIconColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

    /** Subtle decorative icon tint — half-opacity secondary. */
    @Composable
    fun tertiaryIconColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    // ── Charts ────────────────────────────────────────────────────────────────
    // Shared tints for simple inline bar/line charts (e.g. the daily-activity chart in
    // Usage settings) whose bars are colored per-series rather than always primary —
    // centralised here so every chart derives its "track" and "value label" shades from
    // the same two fixed opacities instead of each chart hand-rolling its own alpha.

    /**
     * Faint backdrop tint for a colored chart bar/segment — [hue] at low opacity, giving a
     * "full range" track behind a bar without needing a separate neutral track color.
     */
    fun chartBarTrackColor(hue: Color): Color = hue.copy(alpha = 0.2f)

    /**
     * Value-label tint paired with a chart bar's [hue] — slightly dimmed from full strength
     * so the numeric label reads as secondary to the bar itself.
     */
    fun chartValueLabelColor(hue: Color): Color = hue.copy(alpha = 0.85f)

    /** Placeholder background for a loading skeleton row/box — same neutral tier used for
     *  progress-bar tracks, so skeletons read as "not yet data" rather than a distinct color. */
    @Composable
    fun skeletonPlaceholderColor(): Color = surfaceColor(Elevation.RECESSED)

    // ── Card Content Tints ────────────────────────────────────────────────────
    // A notification/status card's `contentColor` varies by card type (e.g.
    // onSecondaryContainer for an update card, onSurfaceVariant for a plain one) — these
    // derive consistent "badge / secondary / monospace-detail" shades from *whatever*
    // [contentColor] the card is using, so every card type gets the same visual hierarchy
    // without each one hand-picking its own alpha.

    /** Small pill/badge background derived from a card's own [contentColor] (e.g. the
     *  version-bump badge on an update notification card). */
    fun cardBadgeContainerColor(contentColor: Color): Color = contentColor.copy(alpha = 0.15f)

    /** De-emphasized secondary text/caption tint on a colored card (e.g. a notification
     *  card's timestamp) — dimmed from the card's own [contentColor]. */
    fun cardSecondaryContentColor(contentColor: Color): Color = contentColor.copy(alpha = 0.7f)

    /** Expandable monospace detail text (stack trace / error message) on a colored card —
     *  dimmed less than [cardSecondaryContentColor] since code needs to stay legible. */
    fun cardMonospaceDetailColor(contentColor: Color): Color = contentColor.copy(alpha = 0.85f)
}
