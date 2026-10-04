/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent.detectors

import io.askimo.core.intent.BaseIntentDetector
import io.askimo.core.intent.ToolCategory

/**
 * Detector for communication operations (email, messaging, notifications).
 */
class CommunicationDetector :
    BaseIntentDetector(
        category = ToolCategory.COMMUNICATION,
        directKeywords = listOf(
            "send email", "send message", "notify", "alert", "email",
            "message", "slack", "discord", "teams", "send notification",
            "post to", "send to",
        ),
        contextualPatterns = listOf(
            "\\bsend\\b.*\\bemail\\b", "\\bsend\\b.*\\bmessage\\b", "\\bsend\\b.*\\bnotification\\b",
            "\\bsend\\b.*\\bto\\b", "\\bsend\\b.*\\bslack\\b", "\\bsend\\b.*\\bteams\\b", "\\bsend\\b.*\\bdiscord\\b",
            "\\bnotify\\b.*\\buser\\b", "\\bnotify\\b.*\\bteam\\b", "\\bnotify\\b.*\\bpeople\\b",
            "\\balert\\b.*\\buser\\b", "\\balert\\b.*\\bteam\\b", "\\balert\\b.*\\bpeople\\b",
            "\\bemail\\b.*\\bto\\b", "\\bemail\\b.*\\buser\\b", "\\bemail\\b.*\\bteam\\b",
            "\\bmessage\\b.*\\bto\\b", "\\bmessage\\b.*\\buser\\b", "\\bmessage\\b.*\\bteam\\b",
            "\\bpost\\b.*\\bto\\b.*\\bslack\\b", "\\bpost\\b.*\\bto\\b.*\\bteams\\b", "\\bpost\\b.*\\bto\\b.*\\bdiscord\\b",
            "\\bpost\\b.*\\bmessage\\b", "\\bpost\\b.*\\bnotification\\b",
            "\\binform\\b.*\\bteam\\b", "\\binform\\b.*\\buser\\b", "\\binform\\b.*\\bpeople\\b",
            "\\bbroadcast\\b.*\\bmessage\\b", "\\bbroadcast\\b.*\\bnotification\\b",
            "\\bslack\\b.*\\bmessage\\b", "\\bslack\\b.*\\bnotification\\b", "\\bslack\\b.*\\bchannel\\b",
            "\\bteams\\b.*\\bmessage\\b", "\\bteams\\b.*\\bnotification\\b", "\\bteams\\b.*\\bchannel\\b",
            "\\bdiscord\\b.*\\bmessage\\b", "\\bdiscord\\b.*\\bnotification\\b", "\\bdiscord\\b.*\\bchannel\\b",
        ),
    )

/**
 * Detector for monitoring/observability operations.
 */
class MonitoringDetector :
    BaseIntentDetector(
        category = ToolCategory.MONITORING,
        directKeywords = listOf(
            "log", "monitor", "track", "watch", "observe",
            "log event", "track metric", "monitor performance",
            "alert on", "check status", "health check",
        ),
        contextualPatterns = listOf(
            "\\bmonitor\\b.*\\bsystem\\b", "\\bmonitor\\b.*\\bperformance\\b", "\\bmonitor\\b.*\\bstatus\\b",
            "\\bmonitor\\b.*\\bmetrics\\b", "\\bmonitor\\b.*\\bservice\\b", "\\bmonitor\\b.*\\bserver\\b",
            "\\btrack\\b.*\\bmetric\\b", "\\btrack\\b.*\\bmetrics\\b", "\\btrack\\b.*\\bperformance\\b",
            "\\btrack\\b.*\\bevents\\b", "\\btrack\\b.*\\berrors\\b", "\\btrack\\b.*\\busage\\b",
            "\\bwatch\\b.*\\bsystem\\b", "\\bwatch\\b.*\\bstatus\\b", "\\bwatch\\b.*\\bservice\\b",
            "\\bobserve\\b.*\\bmetrics\\b", "\\bobserve\\b.*\\bperformance\\b", "\\bobserve\\b.*\\bsystem\\b",
            "\\blog\\b.*\\bevent\\b", "\\blog\\b.*\\bevents\\b", "\\blog\\b.*\\berror\\b", "\\blog\\b.*\\berrors\\b",
            "\\bcheck\\b.*\\bstatus\\b", "\\bcheck\\b.*\\bhealth\\b", "\\bcheck\\b.*\\bsystem\\b",
            "\\bcheck\\b.*\\bservice\\b", "\\bcheck\\b.*\\bperformance\\b",
            "\\balert\\b.*\\bon\\b", "\\balert\\b.*\\bif\\b", "\\balert\\b.*\\bwhen\\b",
            "\\bhealth\\b.*\\bcheck\\b", "\\bstatus\\b.*\\bcheck\\b",
        ),
    )
