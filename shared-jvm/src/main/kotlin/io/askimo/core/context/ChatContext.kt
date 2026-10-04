/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.context

/**
 * ThreadLocal storage for passing request-scoped context to [io.askimo.core.tools.ToolProviderImpl].
 *
 * Both [projectId] and [enabledServers] are set on the same thread that calls
 * [io.askimo.core.providers.sendStreamingMessageWithCallback], which is the same thread
 * LangChain4j uses to invoke [io.askimo.core.tools.ToolProviderImpl.provideTools].
 * No cross-thread coordination needed.
 */
object ChatContext {
    private val projectIdThreadLocal = ThreadLocal<String?>()
    private val enabledServersThreadLocal = ThreadLocal<Set<String>>()

    fun setProjectId(projectId: String?) = projectIdThreadLocal.set(projectId)
    fun getProjectId(): String? = projectIdThreadLocal.get()

    fun setEnabledServers(enabledServerIds: Set<String>) = enabledServersThreadLocal.set(enabledServerIds)
    fun getEnabledServers(): Set<String> = enabledServersThreadLocal.get() ?: emptySet()

    /**
     * Clear all thread-local state. Must be called in a finally block after each request.
     */
    fun clear() {
        projectIdThreadLocal.remove()
        enabledServersThreadLocal.remove()
    }
}
