/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.indexing

import io.askimo.core.chat.domain.KnowledgeSourceConfig
import io.askimo.core.logging.logger
import io.askimo.core.rag.indexing.providers.LocalFilesIndexingProvider
import io.askimo.core.rag.indexing.providers.LocalFoldersIndexingProvider
import io.askimo.core.rag.indexing.providers.UrlIndexingProvider
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for IndexingCoordinatorProvider instances.
 * Manages the mapping between KnowledgeSourceConfig types and their providers.
 *
 * This registry is self-initializing:
 * - Built-in providers are registered automatically
 * - External providers are discovered via Java ServiceLoader
 *
 * The initialization happens lazily on first access to ensure minimal startup overhead.
 */
object IndexingCoordinatorProviderRegistry {
    private val providers = ConcurrentHashMap<Class<out KnowledgeSourceConfig>, IndexingCoordinatorProvider>()

    private val log = logger<IndexingCoordinatorProvider>()

    @Volatile
    private var initialized = false

    init {
        ensureInitialized()
    }

    /**
     * Ensures the registry is initialized.
     * This is called automatically but can be called explicitly if needed.
     */
    private fun ensureInitialized() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    // Register built-in providers
                    registerBuiltInProviders()

                    // Auto-discover providers via ServiceLoader
                    loadProvidersFromServiceLoader()

                    initialized = true
                }
            }
        }
    }

    /**
     * Register built-in providers that come with Askimo core.
     */
    private fun registerBuiltInProviders() {
        registerProvider(LocalFoldersIndexingProvider())
        registerProvider(LocalFilesIndexingProvider())
        registerProvider(UrlIndexingProvider())
        log.debug("Registered built-in ${providers.size} providers")
    }

    /**
     * Auto-discover and register providers via ServiceLoader.
     */
    private fun loadProvidersFromServiceLoader() {
        try {
            val loader = ServiceLoader.load(IndexingCoordinatorProvider::class.java)
            loader.forEach { provider ->
                registerProvider(provider)
            }
        } catch (e: Exception) {
            // Log error but don't fail initialization
            System.err.println("Failed to load providers from ServiceLoader: ${e.message}")
        }
    }

    /**
     * Register a provider for a specific knowledge source type.
     * If a provider for this type already exists, it will be replaced.
     *
     * @param provider The provider to register
     */
    fun registerProvider(provider: IndexingCoordinatorProvider) {
        providers[provider.supportedType()] = provider
    }

    /**
     * Get provider for a knowledge source.
     * Returns null if no provider is registered for this type.
     *
     * @param knowledgeSource The knowledge source to find a provider for
     * @return The provider if found, null otherwise
     */
    fun getProvider(knowledgeSource: KnowledgeSourceConfig): IndexingCoordinatorProvider? {
        ensureInitialized()
        return providers[knowledgeSource::class.java]
    }

    /**
     * Get all registered providers.
     *
     * @return List of all registered providers
     */
    fun getAllProviders(): List<IndexingCoordinatorProvider> {
        ensureInitialized()
        return providers.values.toList()
    }

    /**
     * Remove all registered providers and reset initialization.
     * Primarily useful for testing.
     */
    fun clear() {
        synchronized(this) {
            providers.clear()
            initialized = false
        }
    }
}
