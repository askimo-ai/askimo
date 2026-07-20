/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.commands

import io.askimo.core.context.AppContext
import io.askimo.core.logging.display
import io.askimo.core.logging.logger
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ProviderRegistry
import io.askimo.core.providers.ProviderSettings
import org.jline.reader.ParsedLine

/**
 * Handles the command to list available models for the current provider.
 *
 * This class retrieves and displays all models that can be used with the currently selected
 * provider. If no models are available, it provides helpful guidance on how to set up the
 * provider correctly, with provider-specific instructions.
 */
class ModelsCommandHandler(
    private val appContext: AppContext,
) : CommandHandler {
    private val log = logger<ModelsCommandHandler>()
    override val keyword: String = ":models"

    override val description: String = "List available models for the current provider"

    override fun handle(line: ParsedLine) {
        val instance = appContext.getActiveInstance()
        val provider = appContext.getActiveProvider()
        val factory = ProviderRegistry.getFactory(provider)

        if (factory == null) {
            log.display("❌ No model factory registered for provider: ${provider.name.lowercase()}")
            return
        }

        val settings = instance?.settings ?: factory.defaultSettings()
        val instanceLabel = instance?.displayName ?: provider.name.lowercase()

        @Suppress("UNCHECKED_CAST")
        val models = try {
            (factory as ChatModelFactory<ProviderSettings>).availableModels(settings)
        } catch (e: Exception) {
            log.display("❌ No models available for '$instanceLabel'")
            log.display("\n💡 ${factory.getNoModelsHelpText()}")
            return
        }

        if (models.isEmpty()) {
            log.display("❌ No models available for '$instanceLabel'")
            log.display("\n💡 ${factory.getNoModelsHelpText()}")
        } else {
            log.display("Available models for '$instanceLabel':")
            models.forEach { log.display("- ${it.displayName}") }
            log.display("\n💡 Use `:set-param model <modelName>` to choose one of these models.")
        }
    }
}
