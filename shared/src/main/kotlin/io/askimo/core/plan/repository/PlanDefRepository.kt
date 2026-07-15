/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.repository

import io.askimo.core.logging.logger
import io.askimo.core.plan.PlanYamlParser
import io.askimo.core.plan.domain.PlanDef
import io.askimo.core.util.AskimoHome
import io.askimo.core.util.walkResourceDirectory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/**
 * Loads [PlanDef] objects from YAML files.
 *
 * ## Two sources
 *
 * ### 1. Built-in plans (classpath resources)
 * YAML files bundled inside the JAR under `/plans/` are loaded at startup.
 * These are read-only (`builtIn = true`) and cannot be deleted by the user.
 *
 * ### 2. User plans (file system)
 * YAML files in `~/.askimo/<profile>/plans/` are loaded on demand and can
 * be created, edited, or deleted by the user.
 *
 * Both sources are merged at [getAll]; user plans shadow built-ins with the same id.
 *
 * ## Caching
 * Built-in plans are cached after first load (they never change at runtime).
 * User plans are always re-read from disk to pick up edits without a restart.
 */
class PlanDefRepository {

    private val log = logger<PlanDefRepository>()

    @Volatile
    private var builtInCache: List<PlanDef>? = null

    /** Raw YAML text for each built-in plan, keyed by plan id. Populated alongside [builtInCache]. */
    @Volatile
    private var builtInYamlCache: Map<String, String> = emptyMap()

    /**
     * Returns the total number of plans (built-ins + user plans) without loading full objects.
     */
    fun count(): Int = getAll().size

    /**
     * Returns all known plans: built-ins + user plans merged.
     * User plans take precedence — a user plan with the same id as a built-in shadows it.
     */
    fun getAll(): List<PlanDef> {
        val builtIns = loadBuiltIns()
        val userPlans = loadUserPlans()

        val userPlanIds = userPlans.map { it.id }.toSet()
        return (userPlans + builtIns.filter { it.id !in userPlanIds })
            .sortedBy { it.name }
    }

    /**
     * Looks up a plan by id. User plans take precedence over built-ins.
     */
    fun findById(id: String): PlanDef? = getAll().find { it.id == id }

    /**
     * Saves a user plan to `~/.askimo/<profile>/plans/<id>.yml`.
     * Overwrites any existing file with the same id.
     *
     * @param yaml Raw YAML text. Must pass [PlanYamlParser.validate] before calling this.
     * @return The parsed [PlanDef] written to disk.
     */
    fun save(yaml: String): PlanDef {
        val plan = PlanYamlParser.parse(yaml)
        val dir = plansDir()
        Files.createDirectories(dir)
        val file = dir.resolve("${plan.id}.yml")
        Files.writeString(file, yaml)
        log.debug("Saved user plan '{}' to {}", plan.id, file)
        return plan
    }

    /**
     * Returns the raw YAML string for a user plan by id, or null if the file doesn't exist.
     * Built-in plans are not readable this way (they live inside the JAR).
     */
    fun loadYaml(id: String): String? {
        val file = plansDir().resolve("$id.yml")
        return if (Files.isRegularFile(file)) Files.readString(file) else null
    }

    /**
     * Returns the raw YAML for either a user plan (from disk) or a built-in plan
     * (from the classpath cache). Used when duplicating a built-in plan.
     */
    fun loadYamlForDuplicate(id: String): String? = loadYaml(id) ?: builtInYamlCache[id]

    /**
     * Deletes a user plan file. Does nothing if no file exists for [id].
     * Built-in plans cannot be deleted.
     *
     * @return `true` if the file was deleted, `false` if it didn't exist.
     */
    fun delete(id: String): Boolean {
        val file = plansDir().resolve("$id.yml")
        return if (Files.deleteIfExists(file)) {
            log.debug("Deleted user plan '{}'", id)
            true
        } else {
            false
        }
    }

    private fun loadBuiltIns(): List<PlanDef> {
        builtInCache?.let { return it }

        val loaded = mutableListOf<PlanDef>()
        val rawYamls = mutableMapOf<String, String>()

        fun loadPlanFile(path: Path) = runCatching {
            val yaml = Files.readString(path)
            val plan = PlanYamlParser.parse(yaml)
            loaded += plan.copy(builtIn = true)
            rawYamls[plan.id] = yaml
            log.debug("Loaded built-in plan '{}' from {}", plan.id, path.fileName)
        }.onFailure { e ->
            log.warn("Skipped built-in plan '{}': {}", path.fileName, e.message)
        }

        val plansUrl = PlanDefRepository::class.java.getResource("/plans/")
        try {
            walkResourceDirectory(plansUrl, "/plans/", "yml") { loadPlanFile(it) }
        } catch (e: Exception) {
            log.warn("Failed to scan built-in plans: {}", e.message)
        }

        builtInCache = loaded
        builtInYamlCache = rawYamls
        log.debug("Loaded {} built-in plan(s)", loaded.size)
        return loaded
    }

    private fun loadUserPlans(): List<PlanDef> {
        val dir = plansDir()
        if (!Files.isDirectory(dir)) return emptyList()

        val loaded = mutableListOf<PlanDef>()
        Files.list(dir)
            .filter { it.isRegularFile() && it.extension == "yml" }
            .sorted()
            .forEach { path ->
                runCatching {
                    val yaml = Files.readString(path)
                    val plan = PlanYamlParser.parse(yaml)
                    loaded += plan
                    log.debug("Loaded user plan '{}' from {}", plan.id, path.nameWithoutExtension)
                }.onFailure { e ->
                    log.warn("Skipped user plan '{}': {}", path.fileName, e.message)
                }
            }

        log.debug("Loaded {} user plan(s) from {}", loaded.size, dir)
        return loaded
    }

    private fun plansDir(): Path = AskimoHome.plansDir()
}
