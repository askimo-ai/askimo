/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent.detectors

import io.askimo.core.intent.BaseIntentDetector
import io.askimo.core.intent.ToolCategory

/**
 * Detector for data transformation operations.
 */
class TransformDetector :
    BaseIntentDetector(
        category = ToolCategory.TRANSFORM,
        directKeywords = listOf(
            "convert", "transform", "parse", "format", "process",
            "convert to", "transform data", "parse json", "parse xml",
            "encode", "decode", "serialize", "deserialize",
        ),
        contextualPatterns = listOf(
            "\\bconvert\\b.*\\bto\\b", "\\bconvert\\b.*\\bjson\\b", "\\bconvert\\b.*\\bxml\\b",
            "\\bconvert\\b.*\\bcsv\\b", "\\bconvert\\b.*\\byaml\\b", "\\bconvert\\b.*\\bformat\\b",
            "\\btransform\\b.*\\bto\\b", "\\btransform\\b.*\\bdata\\b", "\\btransform\\b.*\\bjson\\b",
            "\\btransform\\b.*\\bformat\\b", "\\btransform\\b.*\\binto\\b",
            "\\bparse\\b.*\\bjson\\b", "\\bparse\\b.*\\bxml\\b", "\\bparse\\b.*\\byaml\\b",
            "\\bparse\\b.*\\bcsv\\b", "\\bparse\\b.*\\bdata\\b", "\\bparse\\b.*\\bfile\\b",
            "\\bformat\\b.*\\bas\\b", "\\bformat\\b.*\\bto\\b", "\\bformat\\b.*\\bjson\\b",
            "\\bprocess\\b.*\\bdata\\b", "\\bprocess\\b.*\\bjson\\b", "\\bprocess\\b.*\\bfile\\b",
            "\\bencode\\b.*\\bto\\b", "\\bencode\\b.*\\bas\\b", "\\bencode\\b.*\\bjson\\b",
            "\\bdecode\\b.*\\bfrom\\b", "\\bdecode\\b.*\\bjson\\b", "\\bdecode\\b.*\\bxml\\b",
            "\\bserialize\\b.*\\bto\\b", "\\bserialize\\b.*\\bas\\b", "\\bserialize\\b.*\\bjson\\b",
            "\\bdeserialize\\b.*\\bfrom\\b", "\\bdeserialize\\b.*\\bjson\\b",
            // Only match format patterns when preceded by transformation verbs (avoid overlap with FILE_WRITE)
            "\\bconvert\\b.*to.*\\bjson\\b", "\\bconvert\\b.*to.*\\bxml\\b", "\\bconvert\\b.*to.*\\bcsv\\b",
            "\\btransform\\b.*to.*\\byaml\\b", "\\bparse\\b.*from.*\\bjson\\b",
        ),
    )

/**
 * Detector for version control operations.
 */
class VersionControlDetector :
    BaseIntentDetector(
        category = ToolCategory.VERSION_CONTROL,
        directKeywords = listOf(
            "git", "commit", "push", "pull", "merge", "branch",
            "checkout", "clone", "git commit", "create branch",
            "merge branch", "pull request", "pr",
        ),
        contextualPatterns = listOf(
            "\\bcommit\\b.*\\bchanges\\b", "\\bcommit\\b.*\\bcode\\b", "\\bcommit\\b.*\\bfiles\\b",
            "\\bpush\\b.*\\bto\\b", "\\bpush\\b.*\\bremote\\b", "\\bpush\\b.*\\bbranch\\b",
            "\\bpull\\b.*\\bfrom\\b", "\\bpull\\b.*\\bremote\\b", "\\bpull\\b.*\\bbranch\\b",
            "\\bmerge\\b.*\\bbranch\\b", "\\bmerge\\b.*\\binto\\b", "\\bmerge\\b.*\\bcode\\b",
            "\\bcreate\\b.*\\bbranch\\b", "\\bmake\\b.*\\bbranch\\b", "\\bnew\\b.*\\bbranch\\b",
            "\\bcheckout\\b.*\\bbranch\\b", "\\bswitch\\b.*\\bbranch\\b",
            "\\bclone\\b.*\\brepo\\b", "\\bclone\\b.*\\brepository\\b", "\\bclone\\b.*\\bproject\\b",
            "\\brebase\\b.*\\bbranch\\b", "\\brebase\\b.*\\bonto\\b",
            "\\btag\\b.*\\bversion\\b", "\\btag\\b.*\\brelease\\b",
            "\\bstash\\b.*\\bchanges\\b", "\\bstash\\b.*\\bcode\\b",
            "\\breset\\b.*\\bcommit\\b", "\\breset\\b.*\\bhead\\b",
            "\\brevert\\b.*\\bcommit\\b", "\\brevert\\b.*\\bchanges\\b",
        ),
    )
