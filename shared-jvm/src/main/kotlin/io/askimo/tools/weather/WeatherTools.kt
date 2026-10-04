/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.tools.weather

import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import io.askimo.core.logging.logger
import io.askimo.core.util.httpGet
import io.askimo.tools.ToolResponseBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Weather tools powered by the free Open-Meteo API (https://open-meteo.com).
 * No API key required.
 *
 * - [getCurrentWeather]: Current conditions for any city or location.
 * - [getWeatherForecast]: Multi-day forecast (1–16 days) for any city or location.
 */
object WeatherTools {

    private val log = logger<WeatherTools>()

    private const val CLASS_NAME = "io.askimo.tools.weather.WeatherTools"
    private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val REQUEST_TIMEOUT_MS = 15_000L

    /**
     * Get the current weather for a city or location.
     *
     * @param location City name or location string, e.g. "Tokyo" or "Paris, France"
     * @return JSON with current temperature, weather condition, wind speed, and humidity
     */
    @Tool(
        """Get the current weather for a city or location.

Use this tool when the user asks about:
- Current weather conditions for any city or place
- Temperature right now
- Whether it is raining, sunny, cloudy etc.
- Wind speed or humidity at a location

OUTPUT FORMAT:
Once this tool returns a result, DO NOT call it again.
Summarise the weather directly to the user in plain text. Include:
- Location name and country
- Temperature in °C (and °F if helpful)
- Weather condition description
- Feels-like temperature
- Wind speed and humidity
        """,
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"getCurrentWeather\" }",
    )
    fun getCurrentWeather(
        @P("City name or location, e.g. 'Seattle', 'Paris, France', 'Tokyo'") location: String,
    ): String = try {
        require(location.isNotBlank()) { "Location cannot be empty" }

        val geo = geocode(location)
            ?: return ToolResponseBuilder.failure(
                error = "Could not find location: \"$location\". Try a more specific name (e.g. \"Paris, France\").",
                metadata = mapOf("location" to location),
            )

        val url = "$FORECAST_URL?latitude=${geo.latitude}&longitude=${geo.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature" +
            ",weather_code,wind_speed_10m,wind_direction_10m,precipitation" +
            "&temperature_unit=celsius&wind_speed_unit=kmh&timezone=auto"

        val (weatherStatus, weatherBody) = runCatching {
            httpGet(url, readTimeoutMs = REQUEST_TIMEOUT_MS)
        }.getOrElse { e ->
            log.error("HTTP request failed for getCurrentWeather: {}", e.message)
            return ToolResponseBuilder.failure(
                error = "Network error fetching weather for \"${geo.displayName}\": ${e.message}",
                metadata = mapOf("location" to location),
            )
        }

        if (weatherStatus != 200) {
            return ToolResponseBuilder.failure(
                error = "Weather API returned HTTP $weatherStatus for \"${geo.displayName}\".",
                metadata = mapOf("location" to location, "statusCode" to weatherStatus),
            )
        }

        val current = parseCurrentWeather(weatherBody)
            ?: return ToolResponseBuilder.failure(
                error = "Could not parse weather data for \"${geo.displayName}\".",
                metadata = mapOf("location" to location),
            )

        val temp = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val feelsLike = current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull ?: temp
        val humidity = current["relative_humidity_2m"]?.jsonPrimitive?.intOrNull ?: 0
        val windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val windDir = current["wind_direction_10m"]?.jsonPrimitive?.intOrNull ?: 0
        val weatherCode = current["weather_code"]?.jsonPrimitive?.intOrNull ?: 0
        val precipitation = current["precipitation"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val tempF = String.format("%.1f", temp * 9.0 / 5.0 + 32)

        ToolResponseBuilder.successWithData(
            output = buildString {
                appendLine("Location: ${geo.displayName}")
                appendLine("WMO weather_code: $weatherCode (describe this condition in the user's language)")
                appendLine("Temperature: $temp°C ($tempF°F), feels like $feelsLike°C")
                appendLine("Humidity: $humidity%")
                appendLine("Wind: $windSpeed km/h ${windDegToCompass(windDir)}")
                if (precipitation > 0) appendLine("Precipitation: ${precipitation}mm")
            }.trim(),
            data = mapOf(
                "location" to geo.displayName,
                "country" to geo.country,
                "latitude" to geo.latitude,
                "longitude" to geo.longitude,
                "temperature_celsius" to temp,
                "temperature_fahrenheit" to (tempF.toDoubleOrNull() ?: 0.0),
                "feels_like_celsius" to feelsLike,
                "humidity_percent" to humidity,
                "wind_speed_kmh" to windSpeed,
                "wind_direction_degrees" to windDir,
                "wind_direction" to windDegToCompass(windDir),
                "weather_code" to weatherCode,
                "precipitation_mm" to precipitation,
            ),
        )
    } catch (e: Exception) {
        log.error("Cannot get weather for location {}", location, e)
        ToolResponseBuilder.failure(
            error = "Weather lookup failed: ${e.message}",
            metadata = mapOf("location" to location, "exception" to (e::class.simpleName ?: "Exception")),
        )
    }

    /**
     * Get a 3-day weather forecast for a city or location.
     *
     * @param location City name or location string, e.g. "Tokyo" or "Paris, France"
     * @return JSON with daily high/low temperatures, weather conditions, and precipitation chances
     */
    @Tool(
        """Get a weather forecast for a city or location for 1 to 16 days.

Use this tool when the user asks about:
- Weather over the next few days or weeks
- Whether to pack an umbrella / rain expected
- Temperature range for an upcoming trip
- Weekend weather or any future date range

DAYS PARAMETER:
- Always extract the number of days from the user's question.
- If the user says "next week" use 7. "next 10 days" use 10. "tomorrow" use 1.
- Maximum is 16. If user asks for more, use 16.
- Default to 7 if no specific number is mentioned.

OUTPUT FORMAT:
Present the forecast as a day-by-day summary with date, high/low temp, condition, rain chance.
        """,
        metadata = "{ \"className\": \"$CLASS_NAME\", \"methodName\": \"getWeatherForecast\" }",
    )
    fun getWeatherForecast(
        @P("City name or location, e.g. 'Seattle', 'Paris, France', 'Tokyo'") location: String,
        @P("Number of days to forecast, between 1 and 16. Default 7.") days: Int = 7,
    ): String = try {
        require(location.isNotBlank()) { "Location cannot be empty" }
        val safeDays = days.coerceIn(1, 16)

        val geo = geocode(location)
            ?: return ToolResponseBuilder.failure(
                error = "Could not find location: \"$location\". Try a more specific name (e.g. \"Paris, France\").",
                metadata = mapOf("location" to location, "days" to safeDays),
            )

        val url = "$FORECAST_URL?latitude=${geo.latitude}&longitude=${geo.longitude}" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            ",precipitation_sum,precipitation_probability_max,wind_speed_10m_max" +
            "&temperature_unit=celsius&wind_speed_unit=kmh" +
            "&timezone=auto&forecast_days=$safeDays"

        val (forecastStatus, forecastBody) = runCatching {
            httpGet(url, readTimeoutMs = REQUEST_TIMEOUT_MS)
        }.getOrElse { e ->
            log.error("HTTP request failed for getWeatherForecast: {}", e.message)
            return ToolResponseBuilder.failure(
                error = "Network error fetching forecast for \"${geo.displayName}\": ${e.message}",
                metadata = mapOf("location" to location, "days" to safeDays),
            )
        }

        if (forecastStatus != 200) {
            return ToolResponseBuilder.failure(
                error = "Weather API returned HTTP $forecastStatus for \"${geo.displayName}\".",
                metadata = mapOf("location" to location, "days" to safeDays, "statusCode" to forecastStatus),
            )
        }

        val forecast = parseDailyForecast(forecastBody)

        if (forecast.isEmpty()) {
            return ToolResponseBuilder.failure(
                error = "No forecast data returned for \"${geo.displayName}\".",
                metadata = mapOf("location" to location, "days" to safeDays),
            )
        }

        val summaryLines = forecast.joinToString("\n") { day ->
            "- ${day["date"]}: weather_code=${day["weather_code"]}, High ${day["max_temp_c"]}°C / Low ${day["min_temp_c"]}°C" +
                (if ((day["precipitation_prob"] as? Int ?: 0) > 20) ", rain ${day["precipitation_prob"]}%" else "") +
                (if ((day["precipitation_mm"] as? Double ?: 0.0) > 0.0) " (${day["precipitation_mm"]}mm)" else "")
        }

        ToolResponseBuilder.successWithData(
            output = buildString {
                appendLine("$safeDays-day forecast for ${geo.displayName}.")
                appendLine("WMO weather_code values: describe each condition in the user's language.")
                appendLine(summaryLines)
            }.trim(),
            data = mapOf(
                "location" to geo.displayName,
                "country" to geo.country,
                "latitude" to geo.latitude,
                "longitude" to geo.longitude,
                "days" to safeDays,
                "forecast" to forecast,
            ),
        )
    } catch (e: Exception) {
        ToolResponseBuilder.failure(
            error = "Weather forecast failed: ${e.message}",
            metadata = mapOf("location" to location, "exception" to (e::class.simpleName ?: "Exception")),
        )
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private data class GeoLocation(
        val displayName: String,
        val country: String,
        val latitude: Double,
        val longitude: Double,
    )

    private fun geocode(location: String): GeoLocation? {
        val encoded = java.net.URLEncoder.encode(location.trim(), "UTF-8")
        val (geoStatus, geoBody) = runCatching {
            httpGet(
                "$GEOCODING_URL?name=$encoded&count=1&language=en&format=json",
                readTimeoutMs = REQUEST_TIMEOUT_MS,
            )
        }.getOrElse { return null }
        if (geoStatus != 200) return null
        val body = geoBody
        return try {
            val root = jsonParser.parseToJsonElement(body).jsonObject
            val result = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val name = result["name"]?.jsonPrimitive?.contentOrNull ?: location
            val country = result["country"]?.jsonPrimitive?.contentOrNull ?: ""
            val latitude = result["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
            val longitude = result["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
            GeoLocation(
                displayName = if (country.isNotBlank()) "$name, $country" else name,
                country = country,
                latitude = latitude,
                longitude = longitude,
            )
        } catch (e: Exception) {
            log.warn("Failed to parse geocoding response: {}", e.message)
            null
        }
    }

    private fun parseDailyForecast(body: String): List<Map<String, Any>> {
        return try {
            val root = jsonParser.parseToJsonElement(body).jsonObject
            val daily = root["daily"]?.jsonObject ?: return emptyList()

            fun strings(key: String): List<String> = daily[key]?.jsonArray?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyList()

            fun doubles(key: String): List<Double> = daily[key]?.jsonArray?.map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 } ?: emptyList()

            fun ints(key: String): List<Int> = daily[key]?.jsonArray?.map { (it as? JsonPrimitive)?.intOrNull ?: 0 } ?: emptyList()

            val dates = strings("time")
            val codes = ints("weather_code")
            val maxTemps = doubles("temperature_2m_max")
            val minTemps = doubles("temperature_2m_min")
            val precip = doubles("precipitation_sum")
            val precipProb = ints("precipitation_probability_max")
            val wind = doubles("wind_speed_10m_max")

            if (dates.isEmpty()) return emptyList()

            dates.indices.map { i ->
                val code = codes.getOrElse(i) { 0 }
                mapOf<String, Any>(
                    "date" to (dates.getOrElse(i) { "" }),
                    "condition" to weatherCodeToDescription(code),
                    "weather_code" to code,
                    "max_temp_c" to (maxTemps.getOrElse(i) { 0.0 }),
                    "min_temp_c" to (minTemps.getOrElse(i) { 0.0 }),
                    "precipitation_mm" to (precip.getOrElse(i) { 0.0 }),
                    "precipitation_prob" to (precipProb.getOrElse(i) { 0 }),
                    "wind_speed_max_kmh" to (wind.getOrElse(i) { 0.0 }),
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse forecast response: {}", e.message)
            emptyList()
        }
    }

    private fun parseCurrentWeather(body: String): JsonObject? = try {
        jsonParser.parseToJsonElement(body).jsonObject["current"]?.jsonObject
    } catch (e: Exception) {
        log.warn("Failed to parse current weather response: {}", e.message)
        null
    }

    private fun weatherCodeToDescription(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Foggy"
        48 -> "Icy fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Dense drizzle"
        61 -> "Slight rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66 -> "Freezing rain"
        67 -> "Heavy freezing rain"
        71 -> "Slight snow"
        73 -> "Moderate snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Slight showers"
        81 -> "Moderate showers"
        82 -> "Violent showers"
        85 -> "Slight snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96 -> "Thunderstorm with slight hail"
        99 -> "Thunderstorm with heavy hail"
        else -> "Unknown ($code)"
    }

    private fun windDegToCompass(deg: Int): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((deg + 22.5) / 45).toInt() % 8]
    }
}
