/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.askimo.core.plan.domain.PlanDef
import io.askimo.core.plan.domain.PlanInput
import io.askimo.core.plan.domain.PlanStep
import io.askimo.core.plan.domain.WorkflowNode
import java.io.File
import java.io.InputStream
import java.nio.file.Path

/**
 * Parses a YAML file into a [PlanDef].
 *
 * Supports two styles for the `steps` block:
 *
 * ### Style 1 — Inline list (simple sequential plans)
 * Steps are declared as an ordered list. The parser infers a sequential workflow
 * from declaration order — no `workflow` block needed.
 * ```yaml
 * id: report
 * name: Report
 * steps:
 *   - id: research
 *     message: "Research {{topic}}"
 *   - id: write
 *     message: "Write report. Research: {{research}}"
 * ```
 *
 * ### Style 2 — Explicit map + workflow block (complex plans)
 * Steps are declared as a keyed map and a `workflow` block controls execution topology.
 * ```yaml
 * id: travel-planner
 * name: Travel Planner
 * steps:
 *   assess:
 *     message: "Assess {{destination}}"
 *   itinerary:
 *     message: "Create itinerary"
 *   flights:
 *     message: "Find flights"
 * workflow:
 *   type: sequence
 *   nodes:
 *     - type: step
 *       stepId: assess
 *     - type: parallel
 *       outputKey: research
 *       nodes:
 *         - type: step
 *           stepId: itinerary
 *         - type: conditional
 *           condition: "include_flights == true"
 *           node:
 *             type: step
 *             stepId: flights
 * ```
 *
 * ### Rules
 * - No `workflow` block → infer sequential workflow from step declaration order
 * - `workflow` block present → use it; list order is ignored
 */
object PlanYamlParser {

    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun parse(yaml: String): PlanDef = toModel(mapper.readValue(sanitize(yaml), PlanYaml::class.java))

    fun parse(file: File): PlanDef = parse(file.readText())

    fun parse(path: Path): PlanDef = parse(path.toFile())

    fun parse(stream: InputStream): PlanDef = parse(stream.bufferedReader().readText())

    /** Validates YAML without fully converting — returns error message or null if valid. */
    fun validate(yaml: String): String? = runCatching {
        val raw = mapper.readValue(sanitize(yaml), PlanYaml::class.java)
        val errors = mutableListOf<String>()

        if (raw.id.isBlank()) errors += "Missing required field: id"
        if (raw.name.isBlank()) errors += "Missing required field: name"
        if (raw.steps.isEmpty()) errors += "Plan must have at least one step"

        // Validate that every input has a resolvable key
        raw.inputs.forEachIndexed { i, input ->
            if (input.resolvedKey.isBlank()) errors += "Input #${i + 1} is missing a 'key' (or 'id') field"
        }

        // Validate workflow step references
        raw.workflow?.let { validateWorkflowRefs(it, raw.steps.keys, errors) }

        errors.joinToString("; ").takeIf { it.isNotBlank() }
    }.getOrElse { e -> "Invalid YAML: ${e.message}" }

    /**
     * Pre-processes AI-generated YAML to fix the most common generation mistakes
     * before handing it to Jackson:
     *
     * 1. **Unquoted `message:` / `system:` values containing colons** — YAML interprets
     *    a bare colon-space as a key-value separator, so `message: Do X: then Y` is
     *    invalid. We detect lines where these keys have an unquoted value and wrap the
     *    value in double quotes, escaping any existing double quotes inside.
     *
     * 2. **Unquoted `description:` / `name:` values** — same colon problem.
     *
     * Values that are already quoted (start with `"` or `'`) or are block scalars
     * (`|` / `>`) are left untouched.
     */
    internal fun sanitize(yaml: String): String {
        // Keys whose values are free-form text and commonly contain colons
        val freeTextKeys = setOf("message", "system", "description", "name", "label", "hint", "placeholder")
        val keyPattern = Regex("""^(\s*)(${freeTextKeys.joinToString("|")})\s*:\s*(.+)$""")

        // Step 1: strip `inputs.` prefix from {{inputs.xxx}} placeholders everywhere in the YAML.
        // AI models sometimes generate {{inputs.topic}} instead of the correct {{topic}}.
        val withFixedPlaceholders = yaml.replace(Regex("""\{\{\s*inputs\.(\w[\w\-]*)\s*\}\}"""), "{{$1}}")

        // Step 2: auto-quote unquoted free-text values that contain colons
        return withFixedPlaceholders.lines().joinToString("\n") { line ->
            val match = keyPattern.matchEntire(line)
            if (match != null) {
                val indent = match.groupValues[1]
                val key = match.groupValues[2]
                val value = match.groupValues[3].trim()
                // Leave already-quoted values and block scalars alone
                if (value.startsWith("\"") || value.startsWith("'") ||
                    value.startsWith("|") || value.startsWith(">")
                ) {
                    line
                } else {
                    // Escape any double quotes already in the value, then wrap in double quotes
                    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                    "$indent$key: \"$escaped\""
                }
            } else {
                line
            }
        }
    }

    private fun toModel(raw: PlanYaml): PlanDef {
        val steps = raw.steps.mapValues { (id, s) ->
            PlanStep(
                id = id,
                system = s.system,
                message = s.message,
                ask = s.ask,
                tools = s.tools,
            )
        }

        // If no workflow is declared, default to a sequence over all steps in declaration order
        val workflow = raw.workflow ?: defaultWorkflow(steps)

        return PlanDef(
            id = raw.id,
            name = raw.name,
            icon = raw.icon,
            description = raw.description,
            inputs = raw.inputs.map { i ->
                val resolvedKey = i.resolvedKey
                // If no label is provided, derive one from the key: "my-topic" → "My Topic"
                val resolvedLabel = i.label.ifBlank {
                    resolvedKey.replace(Regex("[-_]"), " ")
                        .split(" ")
                        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
                }
                PlanInput(
                    key = resolvedKey,
                    label = resolvedLabel,
                    type = i.type,
                    options = i.options,
                    default = i.default,
                    required = i.required,
                    hint = i.resolvedHint,
                    filter = i.filter,
                    maxKb = i.maxKb,
                    fetchTimeoutSec = i.fetchTimeoutSec,
                )
            },
            tools = raw.tools,
            steps = steps,
            workflow = workflow,
            builtIn = raw.builtIn,
        )
    }

    /** Fallback: sequence over all steps in declaration order, using Ask nodes where appropriate. */
    private fun defaultWorkflow(steps: Map<String, PlanStep>): WorkflowNode {
        val nodes = steps.entries.map { (id, step) ->
            if (step.ask != null) WorkflowNode.Ask(id) else WorkflowNode.Step(id)
        }
        return when {
            nodes.size == 1 -> nodes.first()
            else -> WorkflowNode.Sequence(nodes)
        }
    }

    private fun validateWorkflowRefs(
        node: WorkflowNode,
        knownStepIds: Set<String>,
        errors: MutableList<String>,
    ) {
        when (node) {
            is WorkflowNode.Step ->
                if (node.stepId !in knownStepIds) {
                    errors += "Workflow references unknown step: '${node.stepId}'"
                }

            is WorkflowNode.Sequence -> node.nodes.forEach { validateWorkflowRefs(it, knownStepIds, errors) }

            is WorkflowNode.Parallel -> node.nodes.forEach { validateWorkflowRefs(it, knownStepIds, errors) }

            is WorkflowNode.Conditional -> validateWorkflowRefs(node.node, knownStepIds, errors)

            is WorkflowNode.Ask ->
                if (node.stepId !in knownStepIds) {
                    errors += "Workflow references unknown ask step: '${node.stepId}'"
                }
        }
    }
}

// ── Raw YAML DTOs ─────────────────────────────────────────────────────────────
// These mirror the YAML structure 1:1 and are only used internally by the parser.

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PlanYaml(
    val id: String = "",
    val name: String = "",
    val icon: String = "",
    val description: String = "",
    val inputs: List<PlanInputYaml> = emptyList(),
    val tools: List<String> = emptyList(),
    @JsonDeserialize(using = StepsDeserializer::class)
    val steps: Map<String, PlanStepYaml> = emptyMap(),
    val workflow: WorkflowNode? = null,
    @param:JsonProperty("built_in")
    val builtIn: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PlanInputYaml(
    // Accept both `key` (canonical) and `id` (common alias used by AI-generated YAML)
    val key: String = "",
    @param:JsonProperty("id")
    private val _id: String = "",
    val label: String = "",
    val type: String = "text",
    val options: List<String> = emptyList(),
    val default: String = "",
    val required: Boolean = false,
    // Accept both `hint` (canonical) and `placeholder` (common alias)
    val hint: String = "",
    @param:JsonProperty("placeholder")
    private val _placeholder: String = "",
    val filter: String = "",
    @param:JsonProperty("max_kb")
    val maxKb: Int = 512,
    @param:JsonProperty("fetch_timeout_sec")
    val fetchTimeoutSec: Int = 10,
) {
    /** Resolved key: prefer explicit `key`, fall back to `id`. */
    val resolvedKey: String get() = key.ifBlank { _id }

    /** Resolved hint: prefer explicit `hint`, fall back to `placeholder`. */
    val resolvedHint: String get() = hint.ifBlank { _placeholder }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PlanStepYaml(
    val id: String = "",
    val system: String? = null,
    val message: String = "",
    val ask: String? = null,
    val tools: List<String> = emptyList(),
)

/**
 * Deserializes the `steps` field from either:
 * - **List style**: `[{id, message, ...}, ...]` — inline sequential, order preserved
 * - **Map style**: `{stepId: {message, ...}, ...}` — explicit keys, order preserved
 *
 * Both forms are normalised into a `LinkedHashMap<String, PlanStepYaml>` so the
 * rest of the parser is style-agnostic.
 */
private class StepsDeserializer : StdDeserializer<Map<String, PlanStepYaml>>(Map::class.java) {

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Map<String, PlanStepYaml> {
        val result = LinkedHashMap<String, PlanStepYaml>()
        val codec = p.codec as ObjectMapper

        return when (p.currentToken) {
            // List style: - id: research\n  message: "..."
            JsonToken.START_ARRAY -> {
                val steps = codec.readValue(p, codec.typeFactory.constructCollectionType(List::class.java, PlanStepYaml::class.java))
                    as List<PlanStepYaml>
                steps.associateByTo(result) { it.id }
                result
            }

            // Map style: research:\n  message: "..."
            JsonToken.START_OBJECT -> {
                val raw = codec.readValue(
                    p,
                    codec.typeFactory.constructMapType(
                        LinkedHashMap::class.java,
                        String::class.java,
                        PlanStepYaml::class.java,
                    ),
                ) as LinkedHashMap<String, PlanStepYaml>
                raw
            }

            else -> result
        }
    }
}
