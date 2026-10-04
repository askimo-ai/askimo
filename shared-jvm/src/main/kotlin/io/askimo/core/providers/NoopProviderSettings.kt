/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

object NoopProviderSettings : ProviderSettings {
    override val defaultModel: String = ""

    override fun describe(): List<String> = listOf()

    override fun getFields(): List<SettingField> = emptyList()

    override fun updateField(fieldName: String, value: String): ProviderSettings = this

    override fun deepCopy(): ProviderSettings = this
}
