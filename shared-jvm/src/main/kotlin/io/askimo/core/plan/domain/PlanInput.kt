/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.plan.domain

/**
 * Declares a user-facing input variable for a [PlanDef].
 *
 * The [key] is used as the placeholder name in step prompts — e.g. a key of
 * `destination` is referenced in YAML as `{{destination}}`.
 *
 * The [type] drives the UI control rendered in the Plan Executor input panel.
 *
 * ### File / folder inputs
 * When `type` is `file` or `folder` the user is shown a file/folder picker.
 * At execution time the selected path(s) are read from disk and their text
 * content is concatenated and injected into the scope under [key], exactly like
 * any other input.  The plan prompt can then reference the content as `{{key}}`.
 *
 * ```yaml
 * inputs:
 *   - key: source_files
 *     type: folder
 *     label: Source folder
 *     filter: "*.kt,*.java"   # comma-separated glob patterns (optional)
 *     max_kb: 256             # hard cap on total injected bytes (optional, default 512 KB)
 * ```
 *
 * ### URL inputs
 * When `type` is `url` the user provides a web URL. At execution time the page is
 * fetched, HTML tags are stripped, and the plain text is injected into scope under [key].
 *
 * ```yaml
 * inputs:
 *   - key: page_content
 *     type: url
 *     label: Web page URL
 *     max_kb: 128
 *     fetch_timeout_sec: 15
 * ```
 */
data class PlanInput(
    /** Variable name — referenced in prompts as `{{key}}`. */
    val key: String,

    /** Human-readable label shown in the input panel. */
    val label: String,

    /**
     * UI control type.
     * Supported values: `text`, `select`, `toggle`, `number`, `multiline`, `file`, `folder`, `url`.
     */
    val type: String = "text",

    /** Options for `type: select`. Empty for all other types. */
    val options: List<String> = emptyList(),

    /** Pre-filled value shown before the user edits. */
    val default: String = "",

    /** Whether the plan refuses to run if this input is blank. */
    val required: Boolean = false,

    /** Optional helper text shown below the input field. */
    val hint: String = "",

    /**
     * Comma-separated glob patterns used to filter which files are read for
     * `type: file` and `type: folder` inputs (e.g. `"*.kt,*.java"`).
     * When blank, all text files are included.
     */
    val filter: String = "",

    /**
     * Maximum total kilobytes of content injected into the scope.
     * Applies to `type: file`, `type: folder`, and `type: url`.
     * Content is truncated with a notice when the limit is exceeded.
     * Default is 512 KB.
     */
    val maxKb: Int = 512,

    /**
     * HTTP fetch timeout in seconds for `type: url` inputs.
     * Default is 10 seconds.
     */
    val fetchTimeoutSec: Int = 10,
)
