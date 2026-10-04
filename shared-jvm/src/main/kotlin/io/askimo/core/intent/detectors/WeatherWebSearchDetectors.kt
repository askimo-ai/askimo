/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.intent.detectors

import io.askimo.core.intent.BaseIntentDetector
import io.askimo.core.intent.ToolCategory

/**
 * Detector for weather-related queries.
 * Triggers on requests for current conditions, forecasts, temperature, and climate data.
 */
class WeatherDetector :
    BaseIntentDetector(
        category = ToolCategory.WEATHER,
        directKeywords = listOf(
            "weather", "forecast", "temperature", "humidity",
            "wind speed", "raining", "snowing", "sunny", "cloudy",
            "precipitation", "climate", "next week weather", "weekly forecast",
        ),
        contextualPatterns = listOf(
            "\\bweather\\b.*\\bin\\b", "\\bweather\\b.*\\bfor\\b",
            "\\bforecast\\b.*\\bfor\\b", "\\bforecast\\b.*\\bin\\b", "\\bforecast\\b.*\\bnext\\b",
            "\\btemperature\\b.*\\bin\\b", "\\btemperature\\b.*\\bat\\b",
            "\\bwill\\b.*\\brain\\b", "\\bwill\\b.*\\bsnow\\b", "\\bwill\\b.*\\bsunny\\b",
            "\\bis\\b.*\\braining\\b", "\\bis\\b.*\\bsnowing\\b",
            "\\bhot\\b.*\\bin\\b", "\\bcold\\b.*\\bin\\b",
            "\\bwhat.*\\bweather\\b", "\\bhow.*\\bweather\\b",
            "\\bpack\\b.*\\bumbrel", "\\bneed\\b.*\\bcoat\\b",
            "\\btrip\\b.*\\bweather\\b", "\\btravel\\b.*\\bweather\\b",
            "\\bweekend\\b.*\\bweather\\b", "\\bweather\\b.*\\bweekend\\b",
            "\\bcurrent\\b.*\\bconditions\\b", "\\bweather\\b.*\\bnow\\b",
            "\\bhow.*\\bwarm\\b", "\\bhow.*\\bcold\\b", "\\bhow.*\\bhot\\b",
            // Multi-day forecast patterns
            "\\bnext\\b.*\\bdays\\b", "\\bnext\\b.*\\bweek\\b",
            "\\b\\d+\\s*days?\\b.*\\bweather\\b", "\\bweather\\b.*\\b\\d+\\s*days?\\b",
            "\\bforecast\\b.*\\b\\d+\\s*days?\\b", "\\b\\d+\\s*days?\\b.*\\bforecast\\b",
            "\\bweather\\b.*\\bnext\\b", "\\bnext\\b.*\\bforecast\\b",
            "\\btomorrow\\b.*\\bweather\\b", "\\bweather\\b.*\\btomorrow\\b",
            "\\bthis\\b.*\\bweek\\b.*\\bweather\\b", "\\bweather\\b.*\\bthis\\b.*\\bweek\\b",
        ),
    )

/**
 * Detector for live web search and page reading queries.
 * Triggers on requests for internet search, browsing URLs, or reading web pages.
 */
class WebSearchDetector :
    BaseIntentDetector(
        category = ToolCategory.WEB_SEARCH,
        directKeywords = listOf(
            "search the web", "web search", "google", "browse", "look it up",
            "search online", "internet search", "brave search",
            "read this url", "read this page", "read this link",
            "open this link", "visit this url", "fetch this page",
            "what does this url say", "latest news", "recent news",
        ),
        contextualPatterns = listOf(
            "\\bsearch\\b.*\\bweb\\b", "\\bsearch\\b.*\\bonline\\b", "\\bsearch\\b.*\\binternet\\b",
            "\\bweb\\b.*\\bsearch\\b", "\\bonline\\b.*\\bsearch\\b",
            "\\bgoogle\\b.*\\bfor\\b", "\\bgoogle\\b.*\\babout\\b",
            "\\bbrowse\\b.*\\bweb\\b", "\\bbrowse\\b.*\\binternet\\b",
            "\\bread\\b.*\\bpage\\b", "\\bread\\b.*\\burl\\b", "\\bread\\b.*\\blink\\b",
            "\\bopen\\b.*\\burl\\b", "\\bopen\\b.*\\blink\\b",
            "\\bvisit\\b.*\\burl\\b", "\\bvisit\\b.*\\bsite\\b", "\\bvisit\\b.*\\bpage\\b",
            "\\bfetch\\b.*\\bpage\\b", "\\bfetch\\b.*\\burl\\b", "\\bfetch\\b.*\\bsite\\b",
            "\\blatest\\b.*\\bnews\\b", "\\bcurrent\\b.*\\bnews\\b", "\\brecent\\b.*\\bnews\\b",
            "\\bwhat.*\\bsays\\b.*\\burl\\b", "\\bwhat.*\\burl\\b.*\\bsay\\b",
            "\\blook\\b.*\\bup\\b.*\\bonline\\b", "\\blook\\b.*\\bup\\b.*\\bweb\\b",
            "https?://\\S+", // direct URL pasted in message
            "\\bwww\\.\\S+", // www. domain reference
        ),
    ) {
    override fun detectDirectKeywords(message: String): Boolean {
        // Check for URLs in the message (http:// or https:// or www.)
        if (message.contains(Regex("https?://\\S+|www\\.\\S+"))) return true
        return directKeywords.any { message.contains(it) }
    }
}
