/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.testcontainers

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import org.testcontainers.ollama.OllamaContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.ConcurrentHashMap

object SharedOllama {
    private val pulledModels = ConcurrentHashMap<String, Boolean>()

    private const val OLLAMA_MODELS_VOLUME = "askimo-ollama-models-cache"

    val container: OllamaContainer by lazy {
        TestContainersConfig.ensureConfigured()

        val image = DockerImageName.parse("ollama/ollama:0.30.8")
        OllamaContainer(image)
            .withReuse(true)
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig?.withBinds(
                    Bind(OLLAMA_MODELS_VOLUME, Volume("/root/.ollama")),
                )
            }
            .apply { start() }
    }

    fun ensureModelPulled(modelName: String) {
        val listResult = container.execInContainer("ollama", "list")
        val modelExists = listResult.stdout.contains(modelName)

        if (modelExists) {
            println("Model $modelName already exists in container (from named volume or previous session), skipping pull")
            pulledModels[modelName] = true
            return
        }

        if (pulledModels.putIfAbsent(modelName, true) == null) {
            println("Pulling model $modelName for the first time in this container...")
            val pullResult = container.execInContainer("ollama", "pull", modelName)
            println("Model pull result - exit code: ${pullResult.exitCode}")
            println("Model pull stdout: ${pullResult.stdout}")
            println("Model pull stderr: ${pullResult.stderr}")

            if (pullResult.exitCode != 0) {
                pulledModels.remove(modelName)
                throw RuntimeException("Failed to pull $modelName model: ${pullResult.stderr}")
            }
            println("Successfully pulled and cached model: $modelName (will persist across test processes)")
        } else {
            println("Model $modelName already verified in this session, skipping pull")
        }
    }
}
