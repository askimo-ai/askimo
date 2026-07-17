/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.commands

import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.logging.display
import io.askimo.core.logging.logger
import io.askimo.core.providers.DefaultMessageResolver
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jline.reader.ParsedLine

/**
 * Handles the command to change the active model provider instance.
 *
 * Supports three forms:
 *  - `:set-provider <instanceName>`      — switch to an existing instance by display name
 *  - `:set-provider <type>`              — switch to the single instance of that type, or create one if none exists
 *  - `:set-provider <type>:<displayName>`— create (or switch to) a named instance of that type
 */
class SetProviderCommandHandler(
    private val appContext: AppContext,
) : CommandHandler {
    private val log = logger<SetProviderCommandHandler>()
    override val keyword: String = ":set-provider"
    override val description: String =
        "Switch provider instance. Usage: :set-provider <instanceName | type | type:name>"

    override fun handle(line: ParsedLine) {
        val args = line.words().drop(1)
        if (args.isEmpty()) {
            log.display("❌ Usage: :set-provider <instanceName | type | type:name>")
            log.display("💡 Use `:providers` to list all configured instances and supported types.")
            return
        }

        val input = args[0].trim()

        // ── Form 1: type:name ──────────────────────────────────────────────────────────────────
        if (input.contains(':')) {
            val parts = input.split(':', limit = 2)
            val typeStr = parts[0]
            val name = parts[1]
            val provider = parseProviderType(typeStr) ?: return
            val existing = appContext.params.providerInstances.firstOrNull {
                it.displayName.equals(name, ignoreCase = true) && it.providerType == provider
            }
            if (existing != null) {
                activateInstance(existing.id, existing.displayName, provider)
            } else {
                val newInstance = ProviderRegistry.createInstance(providerType = provider, displayName = name)
                appContext.upsertInstance(newInstance)
                activateInstance(newInstance.id, name, provider)
            }
            return
        }

        // ── Form 2: match an existing instance by display name ────────────────────────────────
        val byName = appContext.params.providerInstances.firstOrNull {
            it.displayName.equals(input, ignoreCase = true)
        }
        if (byName != null) {
            activateInstance(byName.id, byName.displayName, byName.providerType)
            return
        }

        // ── Form 3: treat input as a provider type ────────────────────────────────────────────
        val provider = runCatching { ModelProvider.valueOf(input.uppercase()) }.getOrNull()
        if (provider != null && ProviderRegistry.getSupportedProviders().contains(provider)) {
            val instances = appContext.params.instancesForType(provider)
            when {
                instances.isEmpty() -> {
                    val newInstance = ProviderRegistry.createInstance(providerType = provider)
                    appContext.upsertInstance(newInstance)
                    activateInstance(newInstance.id, newInstance.displayName, provider)
                }

                instances.size == 1 -> {
                    activateInstance(instances[0].id, instances[0].displayName, provider)
                }

                else -> {
                    log.display("ℹ️  Multiple instances found for '${ProviderRegistry.getProviderDisplayName(provider)}':")
                    instances.forEach { inst ->
                        val marker = if (inst.id == appContext.params.currentInstanceId) " ◀ active" else ""
                        log.display("    • ${inst.displayName}$marker")
                    }
                    log.display("💡 Use `:set-provider ${provider.providerKey()}:<instanceName>` to select one.")
                }
            }
            return
        }

        log.display("❌ No instance or provider type found for: '$input'")
        log.display("💡 Use `:providers` to list all configured instances and supported types.")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun parseProviderType(typeStr: String): ModelProvider? {
        val provider = runCatching { ModelProvider.valueOf(typeStr.uppercase()) }.getOrNull()
        if (provider == null) {
            log.display("❌ Unknown provider type: '$typeStr'")
            log.display("💡 Use `:providers` to list supported types.")
            return null
        }
        if (!ProviderRegistry.getSupportedProviders().contains(provider)) {
            log.display("❌ Provider type '$typeStr' is not registered.")
            return null
        }
        return provider
    }

    private fun activateInstance(instanceId: String, displayName: String, provider: ModelProvider) {
        appContext.setCurrentInstance(instanceId)
        appContext.save()
        CoroutineScope(Dispatchers.Default).launch {
            EventBus.emit(ModelChangedEvent(provider = provider, newModel = "", instanceId = instanceId))
        }
        log.display("✅ Switched to: $displayName (${ProviderRegistry.getProviderDisplayName(provider)})")
        log.display("💡 Use `:models` to list available models for this instance.")
        log.display("💡 Then use `:set-param model <modelName>` to choose one.")

        val settings = appContext.getCurrentProviderSettings()
        if (!settings.validate()) {
            log.display("⚠️  This instance isn't fully configured yet.")
            log.display(settings.getSetupHelpText(DefaultMessageResolver.resolver))
            log.display("👉 Use `:set-param api_key <key>` (or `:set-param base_url <url>`) to finish setup.")
        }
    }
}
