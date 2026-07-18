/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.commands

import io.askimo.core.context.AppContext
import io.askimo.core.context.ParamKey
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.logging.display
import io.askimo.core.logging.displayError
import io.askimo.core.logging.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jline.reader.ParsedLine

/**
 * Handles the command to set model parameters.
 *
 * This class allows users to configure specific parameters for the current model provider,
 * such as temperature, max tokens, or API keys. It validates the parameter key and value,
 * updates the session configuration, and rebuilds the active chat model with the new settings.
 */
class SetParamCommandHandler(
    private val appContext: AppContext,
) : CommandHandler {
    private val log = logger<SetParamCommandHandler>()
    override val keyword = ":set-param"
    override val description = "Set a model parameter (use :params --list for available keys)"

    override fun handle(line: ParsedLine) {
        val args = line.words().drop(1)

        if (args.size != 2) {
            log.display("Usage: :set-param <key> <value>")
            log.display("Type :params --list to see valid keys and descriptions.")
            return
        }

        val keyInput = args[0]
        val valueInput = args[1]

        val key = ParamKey.fromInput(keyInput)
        if (key == null) {
            log.display("Unknown parameter: '$keyInput'. Try :params --list to see valid keys.")
            return
        }

        try {
            val instance = appContext.getActiveInstance()
            if (instance == null) {
                log.display("❌ No active provider instance. Use `:set-provider` to configure one.")
                return
            }
            val provider = instance.providerType
            val providerSettings = appContext.getCurrentProviderSettings()

            key.applyTo(appContext.params, providerSettings, valueInput)
            // Persist mutated settings back into the instance
            appContext.setInstanceSettings(instance.id, providerSettings)
            appContext.save()

            CoroutineScope(Dispatchers.Default).launch {
                EventBus.emit(ModelChangedEvent(provider = provider, newModel = "", instanceId = instance.id))
            }
            log.display("✅ '${key.key}' is updated")
        } catch (e: IllegalArgumentException) {
            log.displayError("❌ Invalid value for '$keyInput': ${e.message}", e)
        }
    }
}
