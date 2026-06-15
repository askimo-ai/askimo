/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.askimo.core.context.AppContext
import io.askimo.core.event.EventBus
import io.askimo.core.event.internal.ModelChangedEvent
import io.askimo.core.logging.logger
import io.askimo.core.providers.ChatModelFactory
import io.askimo.core.providers.ModelDTO
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.ProviderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the model selection dropdown in the footer bar.
 *
 * @param scope Composable-bound scope supplied by [androidx.compose.runtime.rememberCoroutineScope].
 *              All launched coroutines are automatically cancelled when the composable leaves
 *              the composition.
 * @param appContext Application context used to read/write provider settings.
 */
class ModelDropdownState(
    private val scope: CoroutineScope,
    private val appContext: AppContext,
) {
    private val log = logger<ModelDropdownState>()

    /** Models fetched from the current provider. Empty until [loadModels] succeeds. */
    var availableModels by mutableStateOf<List<ModelDTO>>(emptyList())
        private set

    /** True while a model-list fetch is in progress. */
    var isLoading by mutableStateOf(false)
        private set

    /**
     * Fetch available models for [provider] if they have not been loaded yet.
     * Safe to call multiple times; subsequent calls while [availableModels] is non-empty
     * are no-ops unless [reset] was called first.
     */
    fun loadModels(provider: ModelProvider) {
        if (availableModels.isNotEmpty() || isLoading) return
        isLoading = true
        scope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    val settings = appContext.getOrCreateProviderSettings(provider)
                    val factory = appContext.getModelFactory(provider)
                    @Suppress("UNCHECKED_CAST")
                    (factory as? ChatModelFactory<ProviderSettings>)
                        ?.availableModels(settings)
                        ?: emptyList()
                }
                availableModels = models
            } catch (e: Exception) {
                log.error("Failed to load models for provider $provider", e)
                availableModels = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Clear the cached model list (e.g. when the active provider changes so that the
     * next [loadModels] call fetches a fresh list).
     */
    fun reset() {
        availableModels = emptyList()
        isLoading = false
    }

    /**
     * Persist [modelId] as the active model and broadcast a [ModelChangedEvent].
     * Runs IO work off the main thread; the event is emitted on the calling dispatcher
     * after the write completes.
     */
    fun selectModel(provider: ModelProvider, modelId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    appContext.params.model = modelId
                    appContext.save()
                }
                EventBus.emit(ModelChangedEvent(provider, modelId))
            } catch (e: Exception) {
                log.error("Failed to change model to $modelId for provider $provider", e)
            }
        }
    }
}
