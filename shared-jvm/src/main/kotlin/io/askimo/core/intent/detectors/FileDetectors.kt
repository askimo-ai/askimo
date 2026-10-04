/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent.detectors

import io.askimo.core.intent.BaseIntentDetector
import io.askimo.core.intent.ToolCategory

/**
 * Detector for file write operations.
 */
class FileWriteDetector :
    BaseIntentDetector(
        category = ToolCategory.FILE_WRITE,
        directKeywords = listOf(
            "create file", "write file", "save to file", "save as",
            "generate file", "make a file", "write to disk",
            "save this", "write this", "export to", "output to",
        ),
        contextualPatterns = listOf(
            "\\bsave\\b.*\\bfile\\b", "\\bsave\\b.*\\bdisk\\b", "\\bsave\\b.*\\bcsv\\b", "\\bsave\\b.*\\bjson\\b", "\\bsave\\b.*\\btxt\\b",
            "\\bwrite\\b.*\\bfile\\b", "\\bwrite\\b.*\\bdisk\\b", "\\bwrite\\b.*\\bcsv\\b",
            "\\bexport\\b.*\\bfile\\b", "\\bexport\\b.*\\bcsv\\b", "\\bexport\\b.*\\bjson\\b", "\\bexport\\b.*\\btxt\\b",
            "\\boutput\\b.*\\bfile\\b", "\\boutput\\b.*\\bcsv\\b", "\\boutput\\b.*\\bjson\\b",
            "\\bcreate\\b.*\\bfile\\b", "\\bcreate\\b.*\\bcsv\\b", "\\bcreate\\b.*\\bjson\\b",
            "\\bgenerate\\b.*\\bfile\\b", "\\bgenerate\\b.*\\bcsv\\b", "\\bgenerate\\b.*\\bjson\\b",
            "\\bstore\\b.*\\bfile\\b", "\\bstore\\b.*\\bdisk\\b",
            "\\bpersist\\b.*\\bfile\\b", "\\bpersist\\b.*\\bdisk\\b",
            "\\bto\\b.*\\bfile\\b", "\\bto\\b.*\\bcsv\\b", "\\bto\\b.*\\bjson\\b", "\\bto\\b.*\\btxt\\b",
            "\\bin\\b.*\\bfile\\b", "\\bin\\b.*\\bcsv\\b",
            "\\bas\\b.*\\bfile\\b", "\\bas\\b.*\\bcsv\\b", "\\bas\\b.*\\bjson\\b", "\\bas\\b.*\\btxt\\b",
        ),
    )

/**
 * Detector for file read operations.
 */
class FileReadDetector :
    BaseIntentDetector(
        category = ToolCategory.FILE_READ,
        directKeywords = listOf(
            "read file", "open file", "show file", "get file",
            "list files", "display file", "view file", "cat",
            "read from", "load file",
        ),
        contextualPatterns = listOf(
            "\\bread\\b.*\\bfile\\b", "\\bread\\b.*\\bcsv\\b", "\\bread\\b.*\\bjson\\b", "\\bread\\b.*\\btxt\\b",
            "\\bopen\\b.*\\bfile\\b", "\\bopen\\b.*\\bcsv\\b", "\\bopen\\b.*\\bjson\\b",
            "\\bshow\\b.*\\bfile\\b", "\\bshow\\b.*\\bcsv\\b", "\\bshow\\b.*\\bjson\\b",
            "\\bdisplay\\b.*\\bfile\\b", "\\bdisplay\\b.*\\bcsv\\b", "\\bdisplay\\b.*\\bjson\\b",
            "\\bview\\b.*\\bfile\\b", "\\bview\\b.*\\bcsv\\b", "\\bview\\b.*\\bjson\\b",
            "\\bload\\b.*\\bfile\\b", "\\bload\\b.*\\bcsv\\b", "\\bload\\b.*\\bjson\\b", "\\bload\\b.*\\btxt\\b",
            "\\bget\\b.*\\bfile\\b", "\\bget\\b.*\\bcsv\\b", "\\bget\\b.*\\bjson\\b",
            "\\bfetch\\b.*\\bfile\\b", "\\bfetch\\b.*\\bcsv\\b", "\\bfetch\\b.*\\bjson\\b",
            "\\bretrieve\\b.*\\bfile\\b", "\\bretrieve\\b.*\\bcsv\\b",
            "\\bfrom\\b.*\\bfile\\b", "\\bfrom\\b.*\\bcsv\\b", "\\bfrom\\b.*\\bjson\\b", "\\bfrom\\b.*\\btxt\\b",
            "\\bin\\b.*\\bfile\\b", "\\bin\\b.*\\bcsv\\b", "\\bin\\b.*\\bjson\\b",
            "\\blist\\b.*\\bfiles\\b", "\\blist\\b.*\\bfile\\b",
            "\\bshow me\\b.*\\bfile\\b", "\\bshow me\\b.*\\bcsv\\b",
        ),
    )
