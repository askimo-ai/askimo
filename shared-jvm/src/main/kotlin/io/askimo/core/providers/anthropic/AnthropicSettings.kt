/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.anthropic

import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.SettingField

data class AnthropicSettings(
    val baseUrl: String = "https://api.anthropic.com/v1",
    override var apiKey: String = "",
    override val defaultModel: String = "",
    override val utilityModel: String = "",
    override val visionModel: String = "",
    override val imageModel: String = "",
    override val embeddingModel: String = "",
    val maxTokens: Int = 16_000,
    val thinkingBudgetTokens: Int = 0,
    val thinkingMaxTokens: Int = 0,
) : ProviderSettings,
    HasApiKey {
    override fun describe(): List<String> = listOf(
        "apiKey:  ${maskApiKey()}",
        "baseUrl: $baseUrl",
    )

    override fun toString(): String = "AnthropicSettings(baseUrl=$baseUrl, apiKey=${maskApiKey()})"

    override fun getFields(): List<SettingField> = listOf(
        SettingField.TextField(
            name = SettingField.API_KEY,
            label = "API Key",
            description = "Anthropic API key",
            value = apiKey,
            isPassword = true,
        ),
        SettingField.TextField(
            name = SettingField.BASE_URL,
            label = "Base URL",
            description = "Anthropic API base URL",
            value = baseUrl,
        ),
    )

    override fun getConfigurableFields(messageResolver: (String) -> String): List<SettingField> = listOf(
        SettingField.NumberField(
            name = SettingField.MAX_TOKENS,
            label = messageResolver("provider.anthropic.max_tokens.label"),
            description = messageResolver("provider.anthropic.max_tokens.description"),
            value = maxTokens,
        ),
        SettingField.NumberField(
            name = SettingField.THINKING_BUDGET_TOKENS,
            label = messageResolver("provider.anthropic.thinking_budget_tokens.label"),
            description = messageResolver("provider.anthropic.thinking_budget_tokens.description"),
            value = thinkingBudgetTokens,
        ),
        SettingField.NumberField(
            name = SettingField.THINKING_MAX_TOKENS,
            label = messageResolver("provider.anthropic.thinking_max_tokens.label"),
            description = messageResolver("provider.anthropic.thinking_max_tokens.description"),
            value = thinkingMaxTokens,
        ),
    )

    override fun updateField(fieldName: String, value: String): ProviderSettings = when (fieldName) {
        SettingField.API_KEY -> copy(apiKey = value)
        SettingField.BASE_URL -> copy(baseUrl = value)
        SettingField.DEFAULT_MODEL -> copy(defaultModel = value)
        SettingField.UTILITY_MODEL -> copy(utilityModel = value)
        SettingField.VISION_MODEL -> copy(visionModel = value)
        SettingField.IMAGE_MODEL -> copy(imageModel = value)
        SettingField.EMBEDDING_MODEL -> copy(embeddingModel = value)
        SettingField.MAX_TOKENS -> copy(maxTokens = value.toIntOrNull() ?: maxTokens)
        SettingField.THINKING_BUDGET_TOKENS -> copy(thinkingBudgetTokens = value.toIntOrNull() ?: thinkingBudgetTokens)
        SettingField.THINKING_MAX_TOKENS -> copy(thinkingMaxTokens = value.toIntOrNull() ?: thinkingMaxTokens)
        else -> this
    }

    override fun validate(): Boolean = apiKey.isNotBlank()

    override fun getSetupHelpText(messageResolver: (String) -> String): String = messageResolver("provider.anthropic.setup.help")

    override fun getConfigFields(messageResolver: (String) -> String): List<ProviderConfigField> {
        val hasStoredKey = apiKey.isNotBlank() && (apiKey == "***keychain***" || apiKey.startsWith("encrypted:"))

        val description = if (hasStoredKey) {
            messageResolver("provider.anthropic.apikey.stored")
        } else {
            messageResolver("provider.anthropic.apikey.description")
        }

        return listOf(
            ProviderConfigField.ApiKeyField(
                description = description,
                value = apiKey,
                hasExistingValue = hasStoredKey,
            ),
        )
    }

    override fun applyConfigFields(fields: Map<String, String>): ProviderSettings {
        val newApiKey = fields["apiKey"]?.takeIf { it.isNotBlank() } ?: apiKey
        return copy(apiKey = newApiKey)
    }

    override fun deepCopy(): ProviderSettings = copy()
}
