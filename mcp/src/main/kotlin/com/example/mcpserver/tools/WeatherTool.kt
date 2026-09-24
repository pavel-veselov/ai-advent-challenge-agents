package com.example.mcpserver.tools

import com.example.mcpserver.collector.SourceCollector
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Инструмент «Погода» — прогноз погоды по открытому API Open-Meteo.
 *
 * Сетевая логика (геокодинг + текущая погода) вынесена в общий [SourceCollector],
 * чтобы её переиспользовали и планировщик (Day-18). Сам инструмент остаётся тонким
 * декоратором с прежним именем и поведением.
 */
@Component
class WeatherTool(private val collector: SourceCollector) {

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
    ): Mono<Map<String, Any>> = collector.weather(city, units)
}
