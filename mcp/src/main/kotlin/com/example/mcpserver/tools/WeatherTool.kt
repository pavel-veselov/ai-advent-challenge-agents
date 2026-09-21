package com.example.mcpserver.tools

import com.fasterxml.jackson.databind.JsonNode
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * Инструмент «Погода» — прогноз погоды по открытому API Open-Meteo.
 *
 * Двухшаговый вызов: сначала геокодим город (geocoding-api.open-meteo.com), потом по
 * координатам запрашиваем текущую погоду (api.open-meteo.com). Оба шага реактивные.
 */
@Component
class WeatherTool(private val webClient: WebClient) {

    @McpTool(
        name = "get_weather",
        title = "Погода",
        description = "Текущая погода в городе. Принимает название города (на русском или английском), " +
            "опционально единицы температуры (celsius | fahrenheit)."
    )
    fun getWeather(
        @McpToolParam(description = "Название города, например «Москва» или «London».", required = true) city: String,
        @McpToolParam(
            description = "Единицы температуры: celsius (по умолчанию) или fahrenheit.",
            required = false
        ) units: String?
    ): Mono<Map<String, Any>> {
        val temperatureUnit = units?.lowercase() ?: "celsius"

        return webClient.get()
            .uri { ub ->
                ub.scheme("https")
                    .host("geocoding-api.open-meteo.com")
                    .path("/v1/search")
                    .queryParam("name", city)
                    .queryParam("count", 1)
                    .queryParam("language", "ru")
                    .queryParam("format", "json")
                    .build()
            }
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .flatMap { geo -> locate(geo, city, temperatureUnit) }
    }

    private fun locate(geo: JsonNode, city: String, unit: String): Mono<Map<String, Any>> {
        val results = geo.path("results")
        if (!results.isArray || results.isEmpty) {
            return Mono.just(mapOf("error" to "Город не найден: $city"))
        }

        val location = results[0]
        val lat = location.path("latitude").asDouble()
        val lon = location.path("longitude").asDouble()
        val name = location.path("name").asText(city)
        val country = location.path("country").asText("")

        return webClient.get()
            .uri { ub ->
                ub.scheme("https")
                    .host("api.open-meteo.com")
                    .path("/v1/forecast")
                    .queryParam("latitude", lat)
                    .queryParam("longitude", lon)
                    .queryParam("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
                    .queryParam("temperature_unit", unit)
                    .queryParam("timezone", "auto")
                    .build()
            }
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { forecast -> Companion.buildWeather(forecast, name, country, unit) }
    }

    companion object {
        /** Чистая функция сборки ответа о погоде — покрывается тестами без сети. */
        internal fun buildWeather(forecast: JsonNode, city: String, country: String, unit: String): Map<String, Any> {
            val current = forecast.path("current")
            val units = forecast.path("current_units")
            val temp = current.path("temperature_2m").asDouble()
            val feels = current.path("apparent_temperature").asDouble()
            val wind = current.path("wind_speed_10m").asDouble()
            val code = current.path("weather_code").asInt()
            val time = current.path("time").asText("")

            return linkedMapOf(
                "city" to city,
                "country" to country,
                "temperature" to temp,
                "temperature_unit" to units.path("temperature_2m").asText("°C"),
                "apparent_temperature" to feels,
                "wind_speed" to wind,
                "wind_speed_unit" to units.path("wind_speed_10m").asText("km/h"),
                "weather_code" to code,
                "weather" to weatherDescription(code),
                "time" to time,
            )
        }

        internal fun weatherDescription(code: Int): String = when (code) {
            0, 1 -> "Ясно / преимущественно ясно"
            2 -> "Небольшая облачность"
            3 -> "Облачно"
            45, 48 -> "Туман / изморозь"
            51, 53, 55 -> "Морось"
            61, 63, 65 -> "Дождь"
            71, 73, 75 -> "Снег"
            80, 81, 82 -> "Ливень"
            95, 96, 99 -> "Гроза"
            else -> "Код $code"
        }
    }
}
