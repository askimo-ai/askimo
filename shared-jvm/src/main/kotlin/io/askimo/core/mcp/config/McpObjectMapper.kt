/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.mcp.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * Shared [ObjectMapper] for all MCP configuration YAML (de)serialisation.
 *
 * Configured with:
 * - [YAMLFactory] for YAML support
 * - [KotlinModule] for idiomatic Kotlin class mapping
 * - [DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES] disabled so that
 *   older config files remain readable when new fields are added
 *
 * Used by [McpInstancesConfig], and
 * [McpServersConfig] to avoid duplicating mapper construction.
 */
internal val mcpObjectMapper: ObjectMapper =
    ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
