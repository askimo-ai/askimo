/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.cli.graal

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.filter.LevelFilter
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import io.askimo.core.config.AppConfigData
import io.askimo.core.config.EmbeddingConfig
import io.askimo.core.config.IndexingConfig
import io.askimo.core.config.RetryConfig
import io.askimo.core.config.ThrottleConfig
import io.askimo.core.context.AppContextParams
import io.askimo.core.context.ParamKey
import io.askimo.core.providers.AskimoPromptTemplateFactory
import io.askimo.core.providers.HasApiKey
import io.askimo.core.providers.HasBaseUrl
import io.askimo.core.providers.NoopProviderSettings
import io.askimo.core.providers.ProviderSettings
import io.askimo.core.providers.SettingField
import io.askimo.core.providers.anthropic.AnthropicSettings
import io.askimo.core.providers.gemini.GeminiSettings
import io.askimo.core.providers.openai.OpenAiSettings
import io.askimo.core.providers.openaicompatible.OpenAiCompatibleSettings
import io.askimo.tools.fs.LocalFsTools
import io.askimo.tools.git.GitTools
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization
import org.graalvm.nativeimage.hosted.RuntimeReflection

/**
 * GraalVM native image feature that configures runtime reflection and class initialization
 * for Askimo components to ensure proper functionality in compiled native executables.
 */
class AskimoFeature : Feature {
    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        // Register Askimo tool classes invoked via reflection by your ToolRegistry
        registerAllDeclared(LocalFsTools::class.java, GitTools::class.java)

        // Register AskimoPromptTemplateFactory for ServiceLoader + reflection
        registerAllDeclared(
            AskimoPromptTemplateFactory::class.java,
            AskimoPromptTemplateFactory.AskimoTemplate::class.java,
        )
        RuntimeReflection.registerForReflectiveInstantiation(AskimoPromptTemplateFactory::class.java)

        // Register configuration classes for reflection
        registerAllDeclared(
            AppConfigData::class.java,
            EmbeddingConfig::class.java,
            RetryConfig::class.java,
            ThrottleConfig::class.java,
            IndexingConfig::class.java,
            AppContextParams::class.java,
            ParamKey::class.java,
            ProviderSettings::class.java,
            HasApiKey::class.java,
            HasBaseUrl::class.java,
            SettingField::class.java,
            OpenAiSettings::class.java,
            AnthropicSettings::class.java,
            GeminiSettings::class.java,
            OpenAiSettings::class.java,
            OpenAiCompatibleSettings::class.java,
            NoopProviderSettings::class.java,
        )

        // Handle LangChain4j internal Jackson deserializer (package-private, cannot import directly)
        val openAiEmbeddingDeserializer =
            access.findClassByName("dev.langchain4j.model.openai.internal.embedding.OpenAiEmbeddingDeserializer")

        if (openAiEmbeddingDeserializer != null) {
            // Ensure its <clinit> runs at runtime (not during image build)
            RuntimeClassInitialization.initializeAtRunTime(openAiEmbeddingDeserializer)

            // Jackson needs constructors registered for reflection
            RuntimeReflection.register(openAiEmbeddingDeserializer)
            openAiEmbeddingDeserializer.declaredConstructors.forEach { RuntimeReflection.register(it) }
        }

        // Initialize coroutine-related classes at runtime to avoid compilation issues
        RuntimeClassInitialization.initializeAtRunTime("kotlinx.coroutines")
        RuntimeClassInitialization.initializeAtRunTime("kotlin.coroutines")

        RuntimeClassInitialization.initializeAtRunTime("ch.qos.logback")
        registerHierarchy(AsyncAppender::class.java)
        registerAllDeclared(LevelFilter::class.java)

        // Register Logback rolling policies for reflection (needed for logback.xml configuration)
        registerAllDeclared(
            RollingFileAppender::class.java,
            TimeBasedRollingPolicy::class.java,
            SizeAndTimeBasedRollingPolicy::class.java,
        )
    }

    /** Register class + all declared constructors, methods, and fields for reflection. */
    private fun registerAllDeclared(vararg classes: Class<*>) {
        classes.forEach { clazz ->
            RuntimeReflection.register(clazz)
            clazz.declaredConstructors.forEach { RuntimeReflection.register(it) }
            clazz.declaredMethods.forEach { RuntimeReflection.register(it) }
            clazz.declaredFields.forEach { RuntimeReflection.register(it) }
        }
    }

    private fun registerHierarchy(vararg roots: Class<*>) {
        roots.forEach { root ->
            var current: Class<*>? = root
            while (current != null && current != Any::class.java) {
                RuntimeReflection.register(current)

                current.declaredConstructors.forEach { ctor ->
                    RuntimeReflection.register(ctor)
                }

                current.declaredMethods.forEach { method ->
                    RuntimeReflection.register(method)
                }

                current.declaredFields.forEach { field ->
                    RuntimeReflection.register(field)
                }

                current = current.superclass
            }
        }
    }
}
