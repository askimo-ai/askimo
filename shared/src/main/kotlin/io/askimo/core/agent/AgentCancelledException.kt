/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.agent

/**
 * Thrown internally by [ExternalAgentTemplate.run] when the in-flight process was terminated
 * via [ExternalAgent.cancel] rather than exiting on its own (successfully or with a genuine
 * error). Wrapped in the [Result.failure] returned by `run`/`runTracked` — callers (e.g.
 * `AgentRunViewModel`) should check for this type to render a "Cancelled" state instead of
 * treating the turn as failed.
 */
class AgentCancelledException(message: String = "Cancelled by user") : Exception(message)
