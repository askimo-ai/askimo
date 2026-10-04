/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent.detectors

import io.askimo.core.intent.BaseIntentDetector
import io.askimo.core.intent.ToolCategory

/**
 * Detector for command execution operations.
 */
class ExecutionDetector :
    BaseIntentDetector(
        category = ToolCategory.EXECUTE,
        directKeywords = listOf(
            "run", "execute", "install", "build", "compile", "test",
            "run the", "execute this", "install package", "deploy",
            "start", "launch", "trigger",
        ),
        contextualPatterns = listOf(
            "\\brun\\b.*\\btest\\b", "\\brun\\b.*\\bcommand\\b", "\\brun\\b.*\\bscript\\b", "\\brun\\b.*\\bnpm\\b", "\\brun\\b.*\\bbuild\\b",
            "\\bexecute\\b.*\\bcommand\\b", "\\bexecute\\b.*\\bscript\\b", "\\bexecute\\b.*\\btest\\b",
            "\\binstall\\b.*\\bpackage\\b", "\\binstall\\b.*\\bdependency\\b", "\\binstall\\b.*\\bdependencies\\b", "\\binstall\\b.*\\bnpm\\b",
            "\\bbuild\\b.*\\bproject\\b", "\\bbuild\\b.*\\bapp\\b", "\\bbuild\\b.*\\bapplication\\b",
            "\\bcompile\\b.*\\bcode\\b", "\\bcompile\\b.*\\bproject\\b", "\\bcompile\\b.*\\bsource\\b",
            "\\btest\\b.*\\bcode\\b", "\\btest\\b.*\\bproject\\b", "\\btest\\b.*\\bapplication\\b",
            "\\bdeploy\\b.*\\bapp\\b", "\\bdeploy\\b.*\\bapplication\\b", "\\bdeploy\\b.*\\bproject\\b",
            "\\bstart\\b.*\\bserver\\b", "\\bstart\\b.*\\bapp\\b", "\\bstart\\b.*\\bservice\\b",
            "\\blaunch\\b.*\\bapp\\b", "\\blaunch\\b.*\\bapplication\\b", "\\blaunch\\b.*\\bserver\\b",
            "\\btrigger\\b.*\\bbuild\\b", "\\btrigger\\b.*\\btest\\b", "\\btrigger\\b.*\\bdeploy\\b",
            "\\bperform\\b.*\\btest\\b", "\\bperform\\b.*\\bbuild\\b",
            "\\brun\\b.*\\bgradle\\b", "\\brun\\b.*\\bmaven\\b", "\\brun\\b.*\\bmake\\b",
        ),
    )

/**
 * Detector for database operations.
 */
class DatabaseDetector :
    BaseIntentDetector(
        category = ToolCategory.DATABASE,
        directKeywords = listOf(
            "query", "database", "sql", "select from", "insert into",
            "update", "delete from", "create table", "drop table",
            "connect to database", "db query", "run query",
        ),
        contextualPatterns = listOf(
            "\\bquery\\b.*\\bdatabase\\b", "\\bquery\\b.*\\bdb\\b", "\\bquery\\b.*\\btable\\b", "\\bquery\\b.*\\bsql\\b",
            "\\bselect\\b.*\\bfrom\\b", "\\bselect\\b.*\\btable\\b", "\\bselect\\b.*\\bdatabase\\b",
            "\\binsert\\b.*\\binto\\b", "\\binsert\\b.*\\btable\\b", "\\binsert\\b.*\\bdatabase\\b",
            "\\bupdate\\b.*\\btable\\b", "\\bupdate\\b.*\\bdatabase\\b", "\\bupdate\\b.*\\brecord\\b",
            "\\bdelete\\b.*\\bfrom\\b", "\\bdelete\\b.*\\btable\\b", "\\bdelete\\b.*\\brecord\\b",
            "\\bcreate\\b.*\\btable\\b", "\\bcreate\\b.*\\bdatabase\\b",
            "\\bdrop\\b.*\\btable\\b", "\\bdrop\\b.*\\bdatabase\\b",
            "\\bfetch\\b.*\\bdatabase\\b", "\\bfetch\\b.*\\bdb\\b", "\\bfetch\\b.*\\btable\\b",
            "\\bget\\b.*\\bdatabase\\b", "\\bget\\b.*\\bdb\\b", "\\bget\\b.*\\btable\\b",
            "\\bretrieve\\b.*\\bdatabase\\b", "\\bretrieve\\b.*\\bdb\\b", "\\bretrieve\\b.*\\btable\\b",
            "\\bfrom\\b.*\\bdatabase\\b", "\\bfrom\\b.*\\bdb\\b", "\\bfrom\\b.*\\btable\\b",
            "\\bin\\b.*\\bdatabase\\b", "\\bin\\b.*\\bdb\\b", "\\bin\\b.*\\btable\\b",
            "\\bconnect\\b.*\\bdatabase\\b", "\\bconnect\\b.*\\bdb\\b",
            "\\brun\\b.*\\bquery\\b", "\\brun\\b.*\\bsql\\b",
            "\\bexecute\\b.*\\bquery\\b", "\\bexecute\\b.*\\bsql\\b",
            "\\bmysql\\b", "\\bpostgres\\b", "\\bmongodb\\b", "\\bsqlite\\b", "\\boracle\\b", "\\bmssql\\b",
        ),
    )
