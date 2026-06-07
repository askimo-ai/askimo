/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.common.preferences

import io.askimo.core.logging.logger
import io.askimo.core.util.MachineId
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.jvm.java

/**
 * Resettable application preferences — UI layout, onboarding state, and device identity.
 */
object ApplicationPreferences {
    private val prefs = Preferences.userNodeForPackage(ApplicationPreferences::class.java)
    private val log = logger<ApplicationPreferences>()

    // ── Safe preference accessors ─────────────────────────────────────────────

    private fun safePut(key: String, value: String) {
        runCatching { prefs.put(key, value) }
            .onFailure { log.warn("Preferences.put failed for key='$key': ${it.message}") }
    }

    private fun safeGet(key: String, default: String?): String? = runCatching { prefs.get(key, default) }
        .onFailure { log.warn("Preferences.get failed for key='$key': ${it.message}") }
        .getOrDefault(default)

    private fun safePutBoolean(key: String, value: Boolean) {
        runCatching { prefs.putBoolean(key, value) }
            .onFailure { log.warn("Preferences.putBoolean failed for key='$key': ${it.message}") }
    }

    private fun safeGetBoolean(key: String, default: Boolean): Boolean = runCatching { prefs.getBoolean(key, default) }
        .onFailure { log.warn("Preferences.getBoolean failed for key='$key': ${it.message}") }
        .getOrDefault(default)

    private fun safePutInt(key: String, value: Int) {
        runCatching { prefs.putInt(key, value) }
            .onFailure { log.warn("Preferences.putInt failed for key='$key': ${it.message}") }
    }

    private fun safeGetInt(key: String, default: Int): Int = runCatching { prefs.getInt(key, default) }
        .onFailure { log.warn("Preferences.getInt failed for key='$key': ${it.message}") }
        .getOrDefault(default)

    // ============================================================
    // TUTORIAL & ONBOARDING  (resettable — re-shows on clearAll)
    // ============================================================

    private const val TUTORIAL_COMPLETED_KEY = "tutorial_completed"
    private const val LANGUAGE_SELECTED_KEY = "language_selected"
    private const val ONBOARDING_COMPLETED_KEY = "onboarding_completed"

    /** Returns true if language has not been selected yet (first launch). */
    fun isFirstLaunch(): Boolean = !safeGetBoolean(LANGUAGE_SELECTED_KEY, false)

    /** Mark language as selected after the first-time language selection screen. */
    fun markLanguageSelected() = safePutBoolean(LANGUAGE_SELECTED_KEY, true)

    /** Returns true if the in-app tutorial has been completed. */
    fun isTutorialCompleted(): Boolean = safeGetBoolean(TUTORIAL_COMPLETED_KEY, false)

    /** Mark the tutorial as completed. */
    fun markTutorialCompleted() = safePutBoolean(TUTORIAL_COMPLETED_KEY, true)

    /** Returns true if the onboarding wizard has been completed or skipped. */
    fun isOnboardingCompleted(): Boolean = safeGetBoolean(ONBOARDING_COMPLETED_KEY, false)

    /** Mark the onboarding wizard as completed (or skipped). */
    fun markOnboardingCompleted() = safePutBoolean(ONBOARDING_COMPLETED_KEY, true)

    // ============================================================
    // DEVICE IDENTITY  (machine-level, survives user resets)
    // ============================================================

    private const val DEVICE_ID_KEY = "sync.device_id"

    /**
     * Returns a stable device identifier used for echo suppression during sync pull.
     * Machine-level (shared across accounts on the same machine).
     */
    fun getOrCreateDeviceId(): String {
        val cached = safeGet(DEVICE_ID_KEY, null)
        if (cached != null) return cached
        val id = MachineId.resolve() ?: UUID.randomUUID().toString()
        safePut(DEVICE_ID_KEY, id)
        return id
    }

    // ============================================================
    // UI PREFERENCES  (resettable — safe to clear)
    // ============================================================

    private const val PROJECT_SIDE_PANEL_WIDTH_KEY = "ui.project_side_panel_width"
    private const val DEFAULT_PROJECT_SIDE_PANEL_WIDTH = 400
    private const val PROJECT_SIDE_PANEL_EXPANDED_KEY = "ui.project_side_panel_expanded"
    private const val DEFAULT_PROJECT_SIDE_PANEL_EXPANDED = true
    private const val FILE_VIEWER_HEIGHT_RATIO_KEY = "ui.file_viewer_height_ratio"
    private const val DEFAULT_FILE_VIEWER_HEIGHT_RATIO = 50
    private const val PLAN_HISTORY_SIDE_PANEL_WIDTH_KEY = "ui.plan_history_side_panel_width"
    private const val DEFAULT_PLAN_HISTORY_SIDE_PANEL_WIDTH = 320
    private const val PLAN_HISTORY_SIDE_PANEL_EXPANDED_KEY = "ui.plan_history_side_panel_expanded"
    private const val SKILLS_SIDE_PANEL_WIDTH_KEY = "ui.skills_side_panel_width"
    private const val DEFAULT_SKILLS_SIDE_PANEL_WIDTH = 300
    private const val SKILLS_SIDE_PANEL_EXPANDED_KEY = "ui.skills_side_panel_expanded"
    private const val SHOW_PLANS_IN_SIDEBAR_KEY = "ui.show_plans_in_sidebar"
    private const val SHOW_SKILLS_IN_SIDEBAR_KEY = "ui.show_skills_in_sidebar"
    private const val SHOW_PROJECTS_IN_SIDEBAR_KEY = "ui.show_projects_in_sidebar"

    fun getProjectSidePanelWidth(): Int = safeGetInt(PROJECT_SIDE_PANEL_WIDTH_KEY, DEFAULT_PROJECT_SIDE_PANEL_WIDTH)
    fun setProjectSidePanelWidth(width: Int) = safePutInt(PROJECT_SIDE_PANEL_WIDTH_KEY, width)

    fun getProjectSidePanelExpanded(): Boolean = safeGetBoolean(PROJECT_SIDE_PANEL_EXPANDED_KEY, DEFAULT_PROJECT_SIDE_PANEL_EXPANDED)
    fun setProjectSidePanelExpanded(expanded: Boolean) = safePutBoolean(PROJECT_SIDE_PANEL_EXPANDED_KEY, expanded)

    /**
     * File viewer height as a fraction of the RAG sources tab height (0.0–1.0).
     * Stored as an integer percentage.
     */
    fun getFileViewerHeightRatio(): Float = safeGetInt(FILE_VIEWER_HEIGHT_RATIO_KEY, DEFAULT_FILE_VIEWER_HEIGHT_RATIO) / 100f
    fun setFileViewerHeightRatio(ratio: Float) = safePutInt(FILE_VIEWER_HEIGHT_RATIO_KEY, (ratio * 100).toInt().coerceIn(20, 80))

    fun getPlanHistorySidePanelWidth(): Int = safeGetInt(PLAN_HISTORY_SIDE_PANEL_WIDTH_KEY, DEFAULT_PLAN_HISTORY_SIDE_PANEL_WIDTH)
    fun setPlanHistorySidePanelWidth(width: Int) = safePutInt(PLAN_HISTORY_SIDE_PANEL_WIDTH_KEY, width)

    fun getPlanHistorySidePanelExpanded(): Boolean = safeGetBoolean(PLAN_HISTORY_SIDE_PANEL_EXPANDED_KEY, true)
    fun setPlanHistorySidePanelExpanded(expanded: Boolean) = safePutBoolean(PLAN_HISTORY_SIDE_PANEL_EXPANDED_KEY, expanded)

    fun getSkillsSidePanelWidth(): Int = safeGetInt(SKILLS_SIDE_PANEL_WIDTH_KEY, DEFAULT_SKILLS_SIDE_PANEL_WIDTH)
    fun setSkillsSidePanelWidth(width: Int) = safePutInt(SKILLS_SIDE_PANEL_WIDTH_KEY, width)

    fun getSkillsSidePanelExpanded(): Boolean = safeGetBoolean(SKILLS_SIDE_PANEL_EXPANDED_KEY, true)
    fun setSkillsSidePanelExpanded(expanded: Boolean) = safePutBoolean(SKILLS_SIDE_PANEL_EXPANDED_KEY, expanded)

    private const val SKILLS_SELECTED_AGENT_KEY = "ui.skills_selected_agent"
    private const val SKILLS_RIGHT_RAIL_TAB_KEY = "ui.skills_right_rail_tab"

    fun getSkillsSelectedAgentId(): String? = safeGet(SKILLS_SELECTED_AGENT_KEY, null)?.takeIf { it.isNotBlank() }
    fun setSkillsSelectedAgentId(agentId: String) = safePut(SKILLS_SELECTED_AGENT_KEY, agentId)

    /** Returns the last-active tab index in the skills right rail (0 = Skills, 1 = History). */
    fun getSkillsRightRailTab(): Int = safeGetInt(SKILLS_RIGHT_RAIL_TAB_KEY, 0)

    /** Persists the active tab index in the skills right rail. */
    fun setSkillsRightRailTab(index: Int) = safePutInt(SKILLS_RIGHT_RAIL_TAB_KEY, index)

    fun getShowPlansInSidebar(): Boolean = safeGetBoolean(SHOW_PLANS_IN_SIDEBAR_KEY, true)
    fun setShowPlansInSidebar(show: Boolean) = safePutBoolean(SHOW_PLANS_IN_SIDEBAR_KEY, show)

    fun getShowSkillsInSidebar(): Boolean = safeGetBoolean(SHOW_SKILLS_IN_SIDEBAR_KEY, true)
    fun setShowSkillsInSidebar(show: Boolean) = safePutBoolean(SHOW_SKILLS_IN_SIDEBAR_KEY, show)

    fun getShowProjectsInSidebar(): Boolean = safeGetBoolean(SHOW_PROJECTS_IN_SIDEBAR_KEY, true)
    fun setShowProjectsInSidebar(show: Boolean) = safePutBoolean(SHOW_PROJECTS_IN_SIDEBAR_KEY, show)

    // ============================================================
    // LIFECYCLE
    // ============================================================

    /**
     * Clears ALL resettable application preferences (UI layout, onboarding state).
     * Non-resettable fields (launch count, first-use date, analytics consent,
     * update dismissals) are stored in [AccountPreferences] and are NOT affected.
     */
    fun clearAll() {
        runCatching { prefs.clear() }
            .onFailure { log.warn("Preferences.clear() failed: ${it.message}") }
        log.info("All resettable application preferences cleared")
    }
}
