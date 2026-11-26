/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.repository

import io.askimo.core.logging.logger
import io.askimo.core.plan.PlanYamlParser
import io.askimo.core.plan.domain.PlanDef
import io.askimo.core.plan.domain.PlanScope
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
 * ### 2. User plans (file system, PERSONAL scope)
 * YAML files in `~/.askimo/<profile>/plans/` are loaded on demand and can
 * be created, edited, or deleted by the user. Synced across the user's own
 * devices via [io.askimo.core.event.internal.PushDataToServerEvent].
 *
 * Both sources are merged at [getAll]; precedence is user > built-in when ids collide.
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
     * Returns the total number of plans (built-ins + user + team plans) without parsing any YAML.
     * Deduplication is done by filename (which equals plan id), matching the merge logic in [getAll].
     */
    fun count(): Int {
        val userIds = planIds(plansDir())
        val teamIds = planIds(teamPlansDir())
        val builtInIds = builtInIds()
        return buildSet {
            addAll(userIds)
            addAll(teamIds)
            builtInIds.filterTo(this) { it !in userIds && it !in teamIds }
        }.size
    }

    /** Collects plan ids from a directory's filenames without parsing YAML. */
    private fun planIds(dir: Path): Set<String> {
        if (!Files.isDirectory(dir)) return emptySet()
        return Files.list(dir)
            .filter { it.isRegularFile() && it.extension == "yml" }
            .map { it.nameWithoutExtension }
            .toList()
            .toSet()
    }

    /**
     * Returns built-in plan ids.
     * Uses the in-memory cache when available; otherwise scans classpath filenames without parsing.
     */
    private fun builtInIds(): Set<String> {
        builtInCache?.let { return it.map { p -> p.id }.toSet() }
        val ids = mutableSetOf<String>()
        val plansUrl = PlanDefRepository::class.java.getResource("/plans/")
        try {
            walkResourceDirectory(plansUrl, "/plans/", "yml") { path ->
                ids += path.nameWithoutExtension
            }
        } catch (e: Exception) {
            log.warn("Failed to scan built-in plan ids for count: {}", e.message)
        }
        return ids
    }

    /**
     * Returns all known plans: built-ins + team + user plans merged.
     * Precedence when ids collide: user (PERSONAL) > team (TEAM) > built-in.
     */
    fun getAll(): List<PlanDef> {
        val builtIns = loadBuiltIns()
        val teamPlans = loadTeamPlans()
        val userPlans = loadUserPlans()

        val userPlanIds = userPlans.map { it.id }.toSet()
        val teamPlanIds = teamPlans.map { it.id }.toSet()
        return buildList {
            addAll(userPlans)
            teamPlans.filterTo(this) { it.id !in userPlanIds }
            builtIns.filterTo(this) { it.id !in userPlanIds && it.id !in teamPlanIds }
        }.sortedBy { it.name }
    }

    /**
     * Looks up a plan by id. Precedence: user (PERSONAL) > team (TEAM) > built-in.
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
     * Built-in and team plans are not readable this way.
     */
    fun loadYaml(id: String): String? {
        val file = plansDir().resolve("$id.yml")
        return if (Files.isRegularFile(file)) Files.readString(file) else null
    }

    /**
     * Returns the raw YAML for a user plan (from disk), a team plan (from the
     * team-plans cache), or a built-in plan (from the classpath cache), in that
     * order. Used when duplicating any plan — including a TEAM plan — into a
     * new, independently editable copy.
     */
    fun loadYamlForDuplicate(id: String): String? = loadYaml(id) ?: loadTeamYaml(id) ?: builtInYamlCache[id]

    /**
     * Deletes a user plan file. Does nothing if no file exists for [id].
     * Built-in and team plans cannot be deleted this way — use [deleteTeamPlan]
     * for TEAM plans, which is only ever invoked from the sync-pull path.
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

    /**
     * Writes/overwrites a TEAM plan in the local read-only cache
     * (`~/.askimo/<profile>/team-plans/<id>.yml`).
     *
     * **Only the sync-pull path should call this** — TEAM plans are
     * admin-managed server-side; the client never originates them.
     *
     * @param yaml Raw YAML text as received from the server pull response.
     * @return The parsed [PlanDef] written to disk, tagged with [PlanScope.TEAM].
     */
    fun saveTeamPlan(yaml: String): PlanDef {
        val plan = PlanYamlParser.parse(yaml)
        val dir = teamPlansDir()
        Files.createDirectories(dir)
        val file = dir.resolve("${plan.id}.yml")
        Files.writeString(file, yaml)
        log.debug("Saved TEAM plan '{}' to {}", plan.id, file)
        return plan.copy(scope = PlanScope.TEAM)
    }

    /**
     * Deletes a TEAM plan from the local read-only cache. Does nothing if no
     * file exists for [id]. **Only the sync-pull path should call this** —
     * invoked when the server reports the TEAM plan as soft-deleted.
     *
     * @return `true` if the file was deleted, `false` if it didn't exist.
     */
    fun deleteTeamPlan(id: String): Boolean {
        val file = teamPlansDir().resolve("$id.yml")
        return if (Files.deleteIfExists(file)) {
            log.debug("Deleted TEAM plan '{}'", id)
            true
        } else {
            false
        }
    }

    private fun loadTeamYaml(id: String): String? {
        val file = teamPlansDir().resolve("$id.yml")
        return if (Files.isRegularFile(file)) Files.readString(file) else null
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

    private fun loadUserPlans(): List<PlanDef> = loadPlansFrom(plansDir(), scope = PlanScope.PERSONAL, label = "user")

    private fun loadTeamPlans(): List<PlanDef> = loadPlansFrom(teamPlansDir(), scope = PlanScope.TEAM, label = "TEAM")

    private fun loadPlansFrom(dir: Path, scope: PlanScope, label: String): List<PlanDef> {
        if (!Files.isDirectory(dir)) return emptyList()

        val loaded = mutableListOf<PlanDef>()
        Files.list(dir)
            .filter { it.isRegularFile() && it.extension == "yml" }
            .sorted()
            .forEach { path ->
                runCatching {
                    val yaml = Files.readString(path)
                    val plan = PlanYamlParser.parse(yaml)
                    loaded += plan.copy(scope = scope)
                    log.debug("Loaded {} plan '{}' from {}", label, plan.id, path.nameWithoutExtension)
                }.onFailure { e ->
                    log.warn("Skipped {} plan '{}': {}", label, path.fileName, e.message)
                }
            }

        log.debug("Loaded {} {} plan(s) from {}", loaded.size, label, dir)
        return loaded
    }

    private fun plansDir(): Path = AskimoHome.plansDir()

    private fun teamPlansDir(): Path = AskimoHome.teamPlansDir()
}
