/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

/**
 * Represents a configuration field in provider settings.
 */
sealed class SettingField {
    abstract val name: String
    abstract val label: String
    abstract val description: String

    companion object {
        const val API_KEY = "apiKey"
        const val BASE_URL = "baseUrl"
        const val DEFAULT_MODEL = "defaultModel"
        const val UTILITY_MODEL = "utilityModel"
        const val VISION_MODEL = "visionModel"
        const val IMAGE_MODEL = "imageModel"
        const val EMBEDDING_MODEL = "embeddingModel"

        // Provider-specific configurable fields
        const val MAX_TOKENS = "maxTokens"
        const val THINKING_BUDGET_TOKENS = "thinkingBudgetTokens"
        const val THINKING_MAX_TOKENS = "thinkingMaxTokens"
        const val API_MODE = "apiMode"
        const val HTTP_VERSION = "httpVersion"
    }

    data class TextField(
        override val name: String,
        override val label: String,
        override val description: String,
        val value: String,
        val isPassword: Boolean = false,
    ) : SettingField()

    data class NumberField(
        override val name: String,
        override val label: String,
        override val description: String,
        val value: Int,
    ) : SettingField()

    data class EnumField(
        override val name: String,
        override val label: String,
        override val description: String,
        val value: String,
        val options: List<EnumOption>,
    ) : SettingField()

    data class EnumOption(
        val value: String,
        val label: String,
        val description: String,
    )
}
