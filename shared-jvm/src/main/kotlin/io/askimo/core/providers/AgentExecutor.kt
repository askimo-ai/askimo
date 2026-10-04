/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers

import dev.langchain4j.agentic.Agent

interface AgentExecutor {

    @Agent()
    fun execute(): String
}
