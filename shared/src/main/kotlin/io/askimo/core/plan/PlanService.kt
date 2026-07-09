/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.askimo.core.context.AppContext
import io.askimo.core.db.DatabaseManager
import io.askimo.core.logging.logger
import io.askimo.core.plan.domain.PlanDef
import io.askimo.core.plan.domain.PlanExecution
import io.askimo.core.plan.domain.PlanExecutionStatus
import io.askimo.core.plan.domain.PlanStepOutput
import io.askimo.core.plan.repository.PlanDefRepository
import io.askimo.core.plan.repository.PlanExecutionRepository
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates the full lifecycle of a plan run.
 *
 * The service does NOT stream — it blocks until the whole plan finishes.
 * The streaming message will be supported in the future
 */
class PlanService(
    private val planDefRepository: PlanDefRepository,
    private val planExecutionRepository: PlanExecutionRepository = DatabaseManager.getInstance().getPlanExecutionRepository(),
    private val appContext: AppContext,
) {

    private val log = logger<PlanService>()

    /** Returns all available plans (built-ins + user plans), sorted by name. */
    fun getPlans(): List<PlanDef> = planDefRepository.getAll()

    /**
     * Returns the raw YAML string for a user plan by id, or null if not found.
     * Used by the YAML editor to pre-fill content when editing an existing plan.
     */
    fun loadYaml(id: String): String? = planDefRepository.loadYaml(id)

    /**
     * Saves a user plan from raw YAML.
     * Validates the YAML before writing to disk.
     *
     * @return [Result.success] with the parsed [PlanDef], or [Result.failure] with a validation error.
     */
    fun savePlan(yaml: String): Result<PlanDef> {
        val error = PlanYamlParser.validate(yaml)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        return runCatching { planDefRepository.save(yaml) }
    }

    /**
     * Loads the YAML for [id] (user plan or built-in) and returns it with the id
     * suffix "-copy" so the duplicate can be saved as a new user plan.
     *
     * Returns null if the original plan YAML cannot be found.
     */
    fun loadYamlForDuplicate(id: String): String? {
        val yaml = planDefRepository.loadYamlForDuplicate(id) ?: return null
        return yaml
            // Give the copy a unique id so it doesn't shadow the original built-in
            .replace(Regex("(?m)^(id:\\s*)$id(\\s*)$"), "$1$id-copy$2")
            // Remove built_in: true so the duplicate is treated as a user plan
            .replace(Regex("(?m)^built_in:\\s*true\\s*\\n?"), "")
    }

    /**
     * Deletes a user plan by id. Built-in plans are silently skipped.
     *
     * @return `true` if the file was deleted, `false` if no user plan with that id existed.
     */
    fun deletePlan(id: String): Boolean {
        val plan = planDefRepository.findById(id)
        if (plan?.builtIn == true) {
            log.warn("Attempted to delete built-in plan '{}' — ignored", id)
            return false
        }
        return planDefRepository.delete(id)
    }

    /** Returns all execution records for a specific plan, newest first. */
    fun getExecutions(planId: String): List<PlanExecution> = planExecutionRepository.findByPlanId(planId)

    /**
     * Runs a plan synchronously and returns the final AI result.
     *
     * @param planId  Id of the plan to run.
     * @param inputs  User-supplied key→value pairs matching [PlanDef.inputs].
     * @return [Result.success] with the AI output string, or [Result.failure] with the error.
     */
    fun run(planId: String, inputs: Map<String, String>): Result<PlanRunResult> {
        val plan = planDefRepository.findById(planId)
            ?: return Result.failure(IllegalArgumentException("Plan not found: '$planId'"))

        val execution = planExecutionRepository.create(
            PlanExecution(
                id = UUID.randomUUID().toString(),
                planId = plan.id,
                planName = plan.name,
                inputs = inputs,
                status = PlanExecutionStatus.IDLE,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )

        planExecutionRepository.updateStatus(execution.id, PlanExecutionStatus.RUNNING)
        log.info("Starting plan '{}' (execution={})", plan.id, execution.id)

        val chatModel = runCatching { appContext.createChatModel() }.getOrElse { e ->
            val msg = "Failed to create chat model for plan '${plan.id}': ${e.message}"
            log.error(msg, e)
            planExecutionRepository.updateStatus(execution.id, PlanExecutionStatus.FAILED, msg)
            return Result.failure(IllegalStateException(msg, e))
        }

        val executor = PlanExecutor(chatModel)

        var stepOutputs: List<PlanStepOutput> = emptyList()

        return runCatching {
            executor.execute(plan, inputs, execution.id) { stepOutputs = it }
        }.fold(
            onSuccess = { output ->
                val totalInputTokens = stepOutputs.mapNotNull { it.inputTokens }.takeIf { it.isNotEmpty() }?.sum()
                val totalOutputTokens = stepOutputs.mapNotNull { it.outputTokens }.takeIf { it.isNotEmpty() }?.sum()
                val totalTokens = stepOutputs.mapNotNull { it.totalTokens }.takeIf { it.isNotEmpty() }?.sum()
                val totalDurationMs = stepOutputs.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()

                planExecutionRepository.update(
                    execution.copy(
                        status = PlanExecutionStatus.COMPLETED,
                        output = output.takeIf { it.isNotBlank() },
                        stepOutputs = stepOutputs,
                        totalInputTokens = totalInputTokens,
                        totalOutputTokens = totalOutputTokens,
                        totalTokens = totalTokens,
                        totalDurationMs = totalDurationMs,
                    ),
                )
                log.info(
                    "Plan '{}' (execution={}) completed — steps={}, tokens(total/in/out)={}/{}/{}, duration={}ms",
                    plan.id,
                    execution.id,
                    stepOutputs.size,
                    totalTokens ?: "-",
                    totalInputTokens ?: "-",
                    totalOutputTokens ?: "-",
                    totalDurationMs ?: "-",
                )
                Result.success(PlanRunResult(executionId = execution.id, output = output))
            },
            onFailure = { e ->
                val msg = e.message ?: e.javaClass.simpleName
                planExecutionRepository.updateStatus(execution.id, PlanExecutionStatus.FAILED, msg)
                log.error("Plan '{}' (execution={}) failed: {}", plan.id, execution.id, msg, e)
                Result.failure(e)
            },
        )
    }

    /** Deletes a single execution record. */
    fun deleteExecution(executionId: String) = planExecutionRepository.delete(executionId)

    /**
     * Persists an updated [output] string for an existing execution.
     * Used to save AI-fixed diagram content back to the database.
     */
    fun updateExecutionOutput(executionId: String, output: String) {
        val execution = planExecutionRepository.findById(executionId) ?: return
        planExecutionRepository.update(execution.copy(output = output))
    }

    /**
     * Runs a follow-up question against the result of a previous plan execution.
     *
     * The prior plan output is injected as context so the model can refine or extend it
     * without re-running the full multi-step workflow. For multi-step plans, all intermediate
     * step outputs are included so the model has the full chain of reasoning. The [PlanExecution]
     * record's [output] field is updated in place with the new answer, and [runCount] is incremented.
     *
     * @param executionId  The id of the previous [PlanExecution] whose output is the context.
     * @param followUpText The user's follow-up instruction (e.g. "Make it shorter").
     * @return [Result.success] with the new [PlanRunResult], or [Result.failure] with the error.
     */
    fun runFollowUp(executionId: String, followUpText: String): Result<PlanRunResult> {
        val execution = planExecutionRepository.findById(executionId)
            ?: return Result.failure(IllegalArgumentException("Execution not found: '$executionId'"))

        val priorOutput = execution.output?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("No prior output to follow up on"))

        val chatModel = runCatching { appContext.createChatModel() }.getOrElse { e ->
            val msg = "Failed to create chat model for follow-up on execution '$executionId': ${e.message}"
            log.error(msg, e)
            return Result.failure(IllegalStateException(msg, e))
        }

        log.info("Running follow-up on execution '{}': {}", executionId, followUpText.take(80))

        val systemMessage = "You are continuing work on a previously generated result. " +
            "The user wants to refine or extend it. Reply with the complete updated result only."

        // Build context: include intermediate step outputs (if any) so the model has the full
        // chain of reasoning, not just the final answer.
        val userMessage = buildString {
            val stepOutputs = execution.stepOutputs
            if (stepOutputs.size > 1) {
                // Multi-step plan — show each intermediate step's output for full context.
                // The last entry equals priorOutput so we show it separately as "Final result".
                append("This result was produced by a multi-step plan. Here are the intermediate step outputs:\n\n")
                stepOutputs.dropLast(1).forEachIndexed { index, step ->
                    append("### Step ${index + 1}: ${step.stepName}\n\n")
                    append(step.output)
                    append("\n\n")
                }
                append("### Final result\n\n")
                append(priorOutput)
            } else {
                append("Previous result:\n\n")
                append(priorOutput)
            }
            append("\n\n---\n\nUser request: $followUpText")
        }

        return runCatching {
            val response = chatModel.chat(
                SystemMessage.from(systemMessage),
                UserMessage.from(userMessage),
            )
            response.aiMessage().text()
        }.fold(
            onSuccess = { newOutput ->
                val updated = planExecutionRepository.update(
                    execution.copy(
                        output = newOutput.takeIf { it.isNotBlank() } ?: priorOutput,
                        runCount = execution.runCount + 1,
                        status = PlanExecutionStatus.COMPLETED,
                    ),
                )
                log.info("Follow-up on execution '{}' completed, run #{}", executionId, updated.runCount)
                Result.success(PlanRunResult(executionId = executionId, output = newOutput))
            },
            onFailure = { e ->
                log.error("Follow-up on execution '{}' failed: {}", executionId, e.message, e)
                Result.failure(e)
            },
        )
    }

    /**
     * Generates Askimo plan YAML from a plain-English [description] using the active chat model.
     *
     * The model is instructed to output only valid YAML — no markdown fences, no explanation.
     * The result is stripped of any accidental code fences before being returned so it can be
     * fed directly into [PlanYamlParser.validate] and the editor.
     *
     * @param description A plain-English description of what the plan should do.
     * @return The generated YAML string, ready for the editor.
     * @throws Exception if the model call fails or returns blank output.
     */
    fun generateYamlFromPrompt(description: String): String {
        val chatModel = runCatching { appContext.createChatModel() }.getOrElse { e ->
            throw IllegalStateException("Failed to create chat model: ${e.message}", e)
        }

        val systemPrompt = """
            You are an Askimo plan YAML generator.
            Given a plain-English description of a workflow, output ONLY valid Askimo plan YAML — no markdown fences, no explanation, no extra text.

            ════════════════════════════════════════
            FULL SCHEMA REFERENCE
            ════════════════════════════════════════

            ## Top-level fields

            id: string          # REQUIRED. kebab-case, unique plan identifier e.g. "resume-tailor"
            name: string        # REQUIRED. Human-readable display name e.g. "Resume Tailor"
            icon: string        # REQUIRED. A SINGLE emoji character e.g. "🎯". Never a text name.
            description: string # Short description shown in the plan gallery.
            inputs: []          # Ordered list of PlanInput objects (see below). Can be empty. Supports types: text, multiline, number, toggle, select, file, folder.
            tools: []           # Optional list of tool IDs from the ToolRegistry e.g. [WEB_SEARCH].
            steps: {}           # REQUIRED. Map of stepId -> PlanStep (see below).
            workflow:           # REQUIRED. Root WorkflowNode tree (see below).

            ────────────────────────────────────────
            ## PlanInput object (entries in `inputs:` list)

            key: string         # REQUIRED. Variable name. Referenced in prompts as {{key}}. Use snake_case.
            label: string       # REQUIRED. Caption shown in the UI input panel.
            type: string        # REQUIRED. One of: text | multiline | number | toggle | select
            required: boolean   # true if the plan refuses to run when this input is blank.
            default: string     # Pre-filled value. For toggle use "true" or "false".
            hint: string        # Grey placeholder text inside the field. NOT the same as label.
            options: []         # Only for type: select. List of option strings.

            Input type guidance:
            - Use "text" for short single-line strings (name, URL, keyword).
            - Use "multiline" for long text (resume, essay, document, code).
            - Use "number" for numeric values.
            - Use "toggle" for yes/no boolean switches (default: "true" or "false").
            - Use "select" when the user must pick from a fixed set; populate "options".
            - Use "file" when the user should pick one or more local files whose TEXT content is injected into the prompt.
            - Use "folder" when the user should pick a local directory; all matching text files are recursively read and injected.
            - Use "url" when the user provides a web page URL whose text content should be fetched and injected into the prompt.

            Extra fields for type: file and type: folder only:
            filter: string      # Comma-separated glob patterns e.g. "*.kt,*.java". Leave blank to include all text files.
            max_kb: number      # Hard cap on total injected content in kilobytes. Default 512. Lower for large codebases.

            Extra fields for type: url only:
            fetch_timeout_sec: number  # HTTP timeout in seconds. Default 10.
            max_kb: number             # Cap on fetched page content in kilobytes. Default 512.

            file / folder / url usage guidance:
            - Use "file" when the plan needs one specific document (e.g. a resume, a config file, a log file).
            - Use "folder" when the plan should analyse a whole project or directory (e.g. code review, documentation).
            - Always add a meaningful "filter" when the folder is a code project (e.g. "*.kt" for Kotlin, "*.py" for Python).
            - Set max_kb to a sensible value: 128–256 for large codebases, 512 (default) for small documents.
            - Reference injected content in step messages with {{key}} just like any other input.

            ────────────────────────────────────────
            ## PlanStep object (values in `steps:` map)

            # The map key IS the step id — do NOT add an "id:" field inside the step.
            stepId:             # e.g. "analyze-jd" — unique within the plan, used as output key
              system: string    # Optional. System prompt / persona for this step.
              message: string   # The user message sent to the AI. Required for AI steps; omit for ask steps.
              ask: string       # Optional. Interactive question shown to the user at runtime.
                                # When present this step PAUSES execution and waits for user input —
                                # no AI call is made. The answer is stored in scope under the step's id.
                                # Use type: ask in the workflow node (not type: step) for these steps.
              tools: []         # Optional. Step-level tool overrides.

            Template placeholders in system and message:
            - {{inputKey}}  — value of a PlanInput with that key  e.g. {{resume_text}}
            - {{stepId}}    — output of a prior step with that id  e.g. {{analyze-jd}}
            - NEVER use {{inputs.key}}, {{steps.id}}, or any dotted prefix. Plain {{name}} only.

            YAML quoting rules for step values:
            - Wrap all "system" and "message" values in double quotes.
            - Escape any literal double quote inside with \".
            - For multi-line messages use the YAML block scalar (|) — no outer quotes needed then.

            ────────────────────────────────────────
            ## WorkflowNode tree (value of `workflow:`)

            Every node has a "type" discriminator. Five types:

            ### type: step
            type: step
            stepId: string      # REQUIRED. Must match a key in `steps` that has a `message` field.

            ### type: ask
            type: ask
            stepId: string      # REQUIRED. Must match a key in `steps` that has an `ask` field.
                                # Execution pauses; the user's answer is stored in scope under stepId.
                                # Use {{stepId}} in subsequent step messages to reference the answer.

            ### type: sequence
            type: sequence
            nodes:              # REQUIRED. Ordered list of child WorkflowNodes.
              - ...

            ### type: parallel
            type: parallel
            outputKey: string   # Key under which merged output is stored (default: "parallel_result").
            nodes:              # REQUIRED. List of child WorkflowNodes run concurrently.
              - ...

            ### type: conditional
            type: conditional
            condition: string   # REQUIRED. Expression evaluated at runtime:
                                #   "key == value"       exact match (input or prior step output)
                                #   "key == true"        boolean toggle check
                                #   "key contains text"  substring check
            node:               # REQUIRED. Single child WorkflowNode executed when condition is true.
              type: ...

            ════════════════════════════════════════
            GENERATION RULES
            ════════════════════════════════════════

            1. Output raw YAML only — no markdown fences (```), no prose.
            2. plan id must be kebab-case derived from the name.
            3. Every step key in the `steps` map must be unique and referenced correctly in the workflow tree.
            4. For simple sequential plans (steps run top-to-bottom), use:
               workflow:
                 type: sequence
                 nodes:
                   - type: step
                     stepId: first-step
                   - type: step
                     stepId: second-step
               (Never omit the workflow — always include it.)
            5. Later steps SHOULD reference prior step outputs via {{stepId}} in their message so reasoning compounds.
            6. The step map key is the step id — never add a redundant "id:" field inside the step body.
            7. icon MUST be one emoji character. Never write a text word like "lightbulb" or "pencil".
            8. Every input MUST have key, label, type, and required. For type: file or type: folder, add filter and max_kb when the context suggests a specific file type or large directory.
            9. Use "hint" (not "placeholder") for grey hint text.
            10. Do NOT invent tool names. Only include "tools" if the description explicitly requests tools.
        """.trimIndent()

        val response = chatModel.chat(
            SystemMessage.from(systemPrompt),
            UserMessage.from(description),
        )

        val raw = response.aiMessage().text().trim()
        check(raw.isNotBlank()) { "Model returned empty response" }

        // Strip accidental markdown code fences (```yaml ... ``` or ``` ... ```)
        val cleaned = raw
            .removePrefix("```yaml").removePrefix("```")
            .removeSuffix("```")
            .trim()

        // Replace text icon names with their emoji equivalents in case the model ignored the rule
        return replaceTextIconWithEmoji(cleaned)
    }

    /**
     * Replaces common text icon names produced by AI (e.g. `icon: "lightbulb"`) with their
     * emoji equivalents. Unrecognised names are replaced with a generic 📋 fallback.
     */
    private fun replaceTextIconWithEmoji(yaml: String): String {
        val iconNames = mapOf(
            "lightbulb" to "💡", "bulb" to "💡",
            "chart" to "📊", "bar_chart" to "📊", "graph" to "📊",
            "pencil" to "✍️", "edit" to "✍️", "write" to "✍️", "writing" to "✍️",
            "search" to "🔍", "magnify" to "🔍", "magnifying_glass" to "🔍",
            "clipboard" to "📋", "checklist" to "📋", "notepad" to "📋",
            "rocket" to "🚀", "launch" to "🚀",
            "star" to "⭐", "flag" to "🚩",
            "gear" to "⚙️", "settings" to "⚙️", "cog" to "⚙️",
            "book" to "📚", "books" to "📚", "document" to "📄", "file" to "📄",
            "email" to "📧", "mail" to "📧", "envelope" to "📧",
            "calendar" to "📅", "clock" to "🕐", "time" to "🕐",
            "trophy" to "🏆", "award" to "🏆",
            "brain" to "🧠", "idea" to "💡",
            "person" to "👤", "people" to "👥", "team" to "👥",
            "money" to "💰", "dollar" to "💰", "finance" to "💰",
            "computer" to "💻", "code" to "💻", "laptop" to "💻",
            "phone" to "📱", "mobile" to "📱",
            "camera" to "📷", "image" to "🖼️", "photo" to "📷",
            "map" to "🗺️", "location" to "📍", "pin" to "📍",
            "lock" to "🔒", "key" to "🔑", "security" to "🔒",
            "heart" to "❤️", "love" to "❤️",
            "fire" to "🔥", "lightning" to "⚡", "thunder" to "⚡",
            "cloud" to "☁️", "sun" to "☀️", "moon" to "🌙",
            "recycle" to "♻️", "refresh" to "🔄", "loop" to "🔄",
            "warning" to "⚠️", "alert" to "⚠️", "danger" to "⚠️",
            "check" to "✅", "checkmark" to "✅", "done" to "✅",
            "cross" to "❌", "x" to "❌", "no" to "❌",
            "info" to "ℹ️", "information" to "ℹ️",
            "question" to "❓", "help" to "❓",
            "chat" to "💬", "message" to "💬", "comment" to "💬",
            "link" to "🔗", "chain" to "🔗",
            "download" to "⬇️", "upload" to "⬆️",
            "play" to "▶️", "pause" to "⏸️", "stop" to "⏹️",
        )
        // Match: icon: "some-text-name" or icon: some-text-name (no emoji unicode ranges)
        return yaml.replace(Regex("""(?m)^(\s*icon\s*:\s*)"?([A-Za-z_\-]+)"?\s*$""")) { match ->
            val indent = match.groupValues[1]
            val name = match.groupValues[2].lowercase().replace("-", "_")
            val emoji = iconNames[name] ?: iconNames.entries
                .firstOrNull { name.contains(it.key) }?.value
                ?: "📋"
            "${indent}\"$emoji\""
        }
    }
}

/**
 * Returned on a successful [PlanService.run] call.
 *
 * @param executionId The id of the [PlanExecution] record created for this run.
 * @param output      The final AI-generated text produced by the last workflow step.
 */
data class PlanRunResult(
    val executionId: String,
    val output: String,
)
