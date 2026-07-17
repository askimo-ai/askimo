/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.commands

import io.askimo.core.context.AppContext
import io.askimo.core.context.getConfigInfo
import io.askimo.core.logging.display
import io.askimo.core.logging.logger
import io.askimo.core.providers.ProviderRegistry
import org.jline.reader.ParsedLine

/**
 * Handles the command to display the current configuration.
 *
 * Shows the active provider instance name and type, the current model, and all configured
 * settings for the active instance.
 */
class ConfigCommandHandler(
    private val appContext: AppContext,
) : CommandHandler {
    private val log = logger<ConfigCommandHandler>()
    override val keyword: String = ":config"
    override val description: String = "Show the current provider instance, model, and settings."

    override fun handle(line: ParsedLine) {
        val configInfo = appContext.getConfigInfo()
        val typeBadge = ProviderRegistry.getProviderDisplayName(configInfo.provider)

        log.display("🔧 Current configuration:")
        log.display("  Instance:    ${configInfo.instanceDisplayName} [$typeBadge]")
        log.display("  Model:       ${configInfo.model}")
        log.display("  Settings:")

        configInfo.settingsDescription.forEach {
            log.display("    $it")
        }
    }
}
