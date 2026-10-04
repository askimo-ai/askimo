/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Represents a configurable field when setting up a provider.
 */
sealed class ProviderConfigField {
    abstract val name: String
    abstract val label: String
    abstract val description: String
    abstract val required: Boolean

    data class ApiKeyField(
        override val name: String = SettingField.API_KEY,
        override val label: String = "API Key",
        override val description: String,
        override val required: Boolean = true,
        val value: String = "",
        val hasExistingValue: Boolean = false,
    ) : ProviderConfigField()

    data class BaseUrlField(
        override val name: String = SettingField.BASE_URL,
        override val label: String = "Base URL",
        override val description: String,
        override val required: Boolean = true,
        val value: String = "",
    ) : ProviderConfigField()

    data class InfoField(
        override val name: String,
        override val label: String = "",
        override val description: String = "",
        override val required: Boolean = false,
        val message: String,
    ) : ProviderConfigField()

    /**
     * A field that lets the user pick one value from a fixed set of [options].
     * Rendered as a segmented button row in the config UI.
     */
    data class SelectField(
        override val name: String,
        override val label: String,
        override val description: String,
        override val required: Boolean = false,
        val options: List<SelectOption>,
        /** Currently selected option value — must match one of [SelectOption.value]. */
        val value: String,
    ) : ProviderConfigField()
}

/** A single option within a [ProviderConfigField.SelectField]. */
data class SelectOption(
    /** Machine-readable value stored in settings (e.g. enum name). */
    val value: String,
    /** Human-readable label shown in the UI button. */
    val label: String,
    /** Optional subtitle shown below the button row when this option is selected. */
    val description: String = "",
)

/**
 * Result of a provider connection test.
 */
sealed class ProviderTestResult {
    data object Success : ProviderTestResult()
    data class Failure(val message: String, val helpText: String? = null) : ProviderTestResult()
}
