/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.commands

import io.askimo.core.context.AppContext
import io.askimo.core.logging.display
import io.askimo.core.logging.logger
import io.askimo.core.providers.ProviderRegistry
import org.jline.reader.ParsedLine

/**
 * Handles the command to list configured provider instances and supported provider types.
 *
 * Shows all configured instances grouped by provider type (with an active indicator), followed
 * by the full list of registered provider types that can be used to create new instances.
 */
class ListProvidersCommandHandler(
    private val appContext: AppContext,
) : CommandHandler {
    private val log = logger<ListProvidersCommandHandler>()
    override val keyword: String = ":providers"
    override val description: String = "List configured provider instances and supported types"

    override fun handle(line: ParsedLine) {
        val instances = appContext.params.providerInstances
        val currentId = appContext.params.currentInstanceId

        if (instances.isNotEmpty()) {
            log.display("Configured instances:")
            instances.groupBy { it.providerType }.forEach { (type, group) ->
                log.display("  [${ProviderRegistry.getProviderDisplayName(type)}]")
                group.forEach { inst ->
                    val marker = if (inst.id == currentId) " ◀ active" else ""
                    log.display("    • ${inst.displayName}$marker")
                }
            }
            log.display("")
        } else {
            log.display("ℹ️  No provider instances configured yet.")
            log.display("")
        }

        val supportedTypes = ProviderRegistry.getSupportedProviders()
        log.display("Supported provider types:")
        supportedTypes.forEach { provider ->
            log.display("  - ${provider.providerKey()} (${ProviderRegistry.getProviderDisplayName(provider)})")
        }
        log.display("")
        log.display("💡 Use `:set-provider <type>` to switch to or create an instance.")
        log.display("💡 Use `:set-provider <type>:<name>` to create a named instance (e.g. :set-provider ollama:work-mac).")
    }
}
