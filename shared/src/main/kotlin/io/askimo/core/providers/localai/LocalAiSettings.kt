/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.localai

import io.askimo.core.providers.HasBaseUrl
import io.askimo.core.providers.ProviderConfigField
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.SettingField
import kotlinx.serialization.Serializable

@Serializable
data class LocalAiSettings(
    override var baseUrl: String = "http://localhost:8080/v1",
    override val defaultModel: String = "",
    override val utilityModel: String = "",
    override val visionModel: String = "",
    override val imageModel: String = "",
    override val embeddingModel: String = "",
) : ProviderSettings,
    HasBaseUrl {
    override fun describe(): List<String> = listOf(
        "baseUrl:     $baseUrl",
    )

    override fun getFields(): List<SettingField> = listOf(
        SettingField.TextField(
            name = SettingField.BASE_URL,
            label = "Base URL",
            description = "LocalAI server URL",
            value = baseUrl,
        ),
    )

    override fun updateField(fieldName: String, value: String): ProviderSettings = when (fieldName) {
        SettingField.BASE_URL -> copy(baseUrl = value)
        SettingField.DEFAULT_MODEL -> copy(defaultModel = value)
        SettingField.UTILITY_MODEL -> copy(utilityModel = value)
        SettingField.VISION_MODEL -> copy(visionModel = value)
        SettingField.IMAGE_MODEL -> copy(imageModel = value)
        SettingField.EMBEDDING_MODEL -> copy(embeddingModel = value)
        else -> this
    }

    override fun getSetupHelpText(messageResolver: (String) -> String): String = messageResolver("provider.localai.setup.help")

    override fun getConfigFields(messageResolver: (String) -> String): List<ProviderConfigField> = listOf(
        ProviderConfigField.BaseUrlField(
            description = messageResolver("provider.localai.baseurl.description"),
            value = baseUrl,
        ),
    )

    override fun applyConfigFields(fields: Map<String, String>): ProviderSettings {
        val newBaseUrl = fields["baseUrl"] ?: baseUrl
        return copy(baseUrl = newBaseUrl)
    }

    override fun deepCopy(): ProviderSettings = copy()
}
