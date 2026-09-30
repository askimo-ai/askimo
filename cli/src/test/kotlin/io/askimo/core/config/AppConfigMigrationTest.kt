/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.askimo.core.providers.ModelProvider
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.core.util.AskimoHome
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files

@AskimoTestHome
class AppConfigMigrationTest {

    @ParameterizedTest
    @CsvSource(
        "DOCKER, null, DOCKER_AI",
        "DOCKER_AI, null, DOCKER_AI",
        "DOCKER, LOCALAI, LOCALAI",
        "DOCKER_AI, LOCALAI, LOCALAI",
        "LMSTUDIO, null, LMSTUDIO",
        "LMSTUDIO, LOCALAI, LOCALAI",
        "OLLAMA, null, OLLAMA",
        "OLLAMA, LOCALAI, LOCALAI",
        "LOCALAI, null, LOCALAI",
        "LOCALAI, LOCALAI, LOCALAI",
    )
    fun `legacy provider settings migrate without losing configuration`(
        providerType: String,
        templateName: String,
        expectedTemplateName: String,
    ) {
        val configFile = AskimoHome.base().resolve("askimo.yml")
        Files.writeString(
            configFile,
            """
            models:
              max_tool_calling_round_trips: 25
            context:
              current_instance_id: docker-local
              provider_instances:
                - id: docker-local
                  display_name: Local Docker
                  provider_type: "$providerType"
                  settings:
                    type: docker
                    base_url: http://localhost:12434/v1
                    default_model: ai/llama3.2:latest
                    utility_model: ai/qwen3:latest
                    embedding_model: ai/mxbai-embed-large:latest
                    template_name: $templateName
            """.trimIndent(),
        )
        AppConfig.reset()

        val context = AppConfig.context
        assertEquals("docker-local", context.currentInstanceId)
        assertEquals(1, context.providerInstances.size)
        val instance = context.providerInstances.single()
        assertEquals("docker-local", instance.id)
        assertEquals("Local Docker", instance.displayName)
        assertEquals(ModelProvider.OPENAI_COMPATIBLE, instance.providerType)
        val settings = instance.settings as OpenAiCompatibleSettings
        assertEquals(expectedTemplateName, settings.templateName)
        assertEquals("http://localhost:12434/v1", settings.baseUrl)
        assertEquals("ai/llama3.2:latest", settings.defaultModel)
        assertEquals("ai/qwen3:latest", settings.utilityModel)
        assertEquals("ai/mxbai-embed-large:latest", settings.embeddingModel)
        assertEquals(25, AppConfig.models.maxToolCallingRoundTrips)

        val migratedYaml = Files.readString(configFile)
        val persistedInstance = ObjectMapper(YAMLFactory()).readTree(migratedYaml)
            .path("context").path("provider_instances").get(0)
        assertEquals("OPENAI_COMPATIBLE", persistedInstance.path("provider_type").asText())
        assertEquals(expectedTemplateName, persistedInstance.path("settings").path("template_name").asText())

        AppConfig.reset()
        assertEquals(context, AppConfig.context)
        assertEquals(migratedYaml, Files.readString(configFile))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "template_name: null",
            "template_name: ~",
            "template_name: \"\"",
            "template_name: ''",
            "template_name:",
            "template_name: null # default",
            "",
        ],
    )
    fun `legacy provider migration handles YAML quoting field order and empty templates`(templateLine: String) {
        val configFile = AskimoHome.base().resolve("askimo.yml")
        Files.writeString(
            configFile,
            """
            context:
              current_instance_id: docker-local
              provider_instances:
                - settings:
                    type: docker
                    base_url: http://localhost:12434/v1
                    default_model: local-model
                    $templateLine
                  provider_type: 'DOCKER'
                  display_name: Local Docker
                  id: docker-local
            """.trimIndent(),
        )
        AppConfig.reset()

        val instance = AppConfig.context.providerInstances.single()
        assertEquals(ModelProvider.OPENAI_COMPATIBLE, instance.providerType)
        val settings = instance.settings as OpenAiCompatibleSettings
        assertEquals("DOCKER_AI", settings.templateName)
        assertEquals("http://localhost:12434/v1", settings.baseUrl)
        assertEquals("local-model", settings.defaultModel)

        val migratedYaml = Files.readString(configFile)
        AppConfig.reset()
        assertEquals(instance, AppConfig.context.providerInstances.single())
        assertEquals(migratedYaml, Files.readString(configFile))
    }

    @ParameterizedTest
    @CsvSource(
        "DOCKER, LOCALAI, LOCALAI",
        "DOCKER, MISSING, DOCKER_AI",
        "DOCKER_AI, LOCALAI, LOCALAI",
        "DOCKER_AI, MISSING, DOCKER_AI",
        "LMSTUDIO, LOCALAI, LOCALAI",
        "LMSTUDIO, MISSING, LMSTUDIO",
        "OLLAMA, LOCALAI, LOCALAI",
        "OLLAMA, MISSING, OLLAMA",
        "LOCALAI, LOCALAI, LOCALAI",
        "LOCALAI, MISSING, LOCALAI",
    )
    fun `legacy provider migration does not change neighboring providers or unrelated fields`(
        providerType: String,
        templateName: String,
        expectedTemplateName: String,
    ) {
        val templateLine = if (templateName == "MISSING") "" else "template_name: $templateName"
        val configFile = AskimoHome.base().resolve("askimo.yml")
        Files.writeString(
            configFile,
            """
            notes: 'provider_type: DOCKER'
            custom_section:
              values: [true, 13, unchanged]
            context:
              current_instance_id: custom
              provider_instances:
                - id: legacy
                  display_name: Legacy
                  provider_type: $providerType
                  settings:
                    type: openai_compatible
                    base_url: http://localhost:12434/v1
                    $templateLine
                - id: custom
                  display_name: Custom
                  provider_type: OPENAI_COMPATIBLE
                  settings:
                    type: openai_compatible
                    base_url: http://localhost:8000/v1
                    default_model: custom-model
                    template_name: null
                    custom_setting: keep
                - id: lmstudio
                  display_name: LM Studio
                  provider_type: LMSTUDIO
                  settings:
                    type: lmstudio
                    base_url: http://localhost:1234/v1
                    template_name: null
            """.trimIndent(),
        )
        val yamlMapper = ObjectMapper(YAMLFactory())
        val original = yamlMapper.readTree(Files.readString(configFile))
        AppConfig.reset()

        val context = AppConfig.context
        assertEquals("custom", context.currentInstanceId)
        assertEquals(listOf("legacy", "custom", "lmstudio"), context.providerInstances.map { it.id })
        assertTrue(context.providerInstances.all { it.providerType == ModelProvider.OPENAI_COMPATIBLE })
        val settings = context.providerInstances.map { it.settings as OpenAiCompatibleSettings }
        assertEquals(expectedTemplateName, settings[0].templateName)
        assertEquals(null, settings[1].templateName)
        assertEquals("custom-model", settings[1].defaultModel)
        assertEquals("LMSTUDIO", settings[2].templateName)

        val migratedYaml = Files.readString(configFile)
        val persisted = yamlMapper.readTree(migratedYaml)
        assertEquals(original.path("notes"), persisted.path("notes"))
        assertEquals(original.path("custom_section"), persisted.path("custom_section"))
        assertEquals(
            original.path("context").path("provider_instances").get(1),
            persisted.path("context").path("provider_instances").get(1),
        )

        AppConfig.reset()
        assertEquals(context, AppConfig.context)
        assertEquals(migratedYaml, Files.readString(configFile))
    }

    @Test
    fun `config without legacy providers is not rewritten`() {
        val configFile = AskimoHome.base().resolve("askimo.yml")
        val yaml = """
            # Keep comments and formatting when no migration is needed
            notes: 'provider_type: DOCKER'
            context:
              current_instance_id: custom
              provider_instances:
                - id: custom
                  display_name: Custom
                  provider_type: OPENAI_COMPATIBLE
                  settings:
                    type: openai_compatible
                    base_url: http://localhost:8000/v1
                    template_name: null
        """.trimIndent()
        Files.writeString(configFile, yaml)
        AppConfig.reset()

        val instance = AppConfig.context.providerInstances.single()
        assertEquals(ModelProvider.OPENAI_COMPATIBLE, instance.providerType)
        assertEquals(null, (instance.settings as OpenAiCompatibleSettings).templateName)
        assertEquals(yaml, Files.readString(configFile))
    }

    @Test
    fun `invalid legacy config is not rewritten`() {
        val configFile = AskimoHome.base().resolve("askimo.yml")
        val yaml = """
            models:
              max_tool_calling_round_trips: not-a-number
            context:
              provider_instances:
                - id: docker-local
                  display_name: Local Docker
                  provider_type: DOCKER
                  settings:
                    type: docker
                    template_name: null
        """.trimIndent()
        Files.writeString(configFile, yaml)
        AppConfig.reset()

        assertTrue(AppConfig.context.providerInstances.isEmpty())
        assertEquals(yaml, Files.readString(configFile))
    }
}
