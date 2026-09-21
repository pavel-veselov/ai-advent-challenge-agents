package com.example.mcpserver.tools

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты чистой логики разбора ответов внешних API — без обращения к сети.
 * Проверяем парсинг JSON в готовые Map, которые отдаёт MCP-инструмент.
 */
class ToolParsingTest {

    private val mapper = ObjectMapper()

    @Test
    fun `cbr parseRates возвращает курс по конкретному коду`() {
        val json = """
            {
              "Date": "2024-05-20",
              "Valute": {
                "USD": {"CharCode":"USD","Nominal":1,"Value":90.5,"Previous":89.9,"Name":"Доллар США"},
                "EUR": {"CharCode":"EUR","Nominal":1,"Value":98.0,"Previous":97.5,"Name":"Евро"}
              }
            }
        """.trimIndent()

        val result = CurrencyTool.parseRates(mapper.readTree(json), "USD")

        assertEquals("RUB", result["base"])
        assertEquals("2024-05-20", result["date"])
        @Suppress("UNCHECKED_CAST")
        val usd = (result["rates"] as Map<String, Any>)["USD"] as Map<String, Any>
        assertTrue((usd["rate"] as Double) > 90.0)
        assertTrue((usd["change_from_previous"] as Double) > 0.0)
        assertTrue((result["rates"] as Map<String, Any>)["EUR"] == null, "EUR не должен попасть при фильтре по USD")
    }

    @Test
    fun `cbr parseRates без кода возвращает все валюты`() {
        val json = """
            {
              "Date": "2024-05-20",
              "Valute": {
                "USD": {"CharCode":"USD","Nominal":1,"Value":90.5,"Previous":89.9,"Name":"Доллар США"},
                "EUR": {"CharCode":"EUR","Nominal":1,"Value":98.0,"Previous":97.5,"Name":"Евро"}
              }
            }
        """.trimIndent()

        val result = CurrencyTool.parseRates(mapper.readTree(json), null)
        @Suppress("UNCHECKED_CAST")
        val rates = result["rates"] as Map<String, Any>
        assertEquals(2, rates.size)
    }

    @Test
    fun `weather buildWeather собирает текущую погоду`() {
        val json = """
            {
              "current": {
                "temperature_2m": 18.5,
                "apparent_temperature": 17.0,
                "weather_code": 2,
                "wind_speed_10m": 11.2,
                "time": "2024-05-20T12:00"
              },
              "current_units": {
                "temperature_2m": "°C",
                "wind_speed_10m": "km/h"
              }
            }
        """.trimIndent()

        val result = WeatherTool.buildWeather(mapper.readTree(json), "Москва", "Россия", "celsius")

        assertEquals("Москва", result["city"])
        assertEquals(18.5, result["temperature"])
        assertEquals("Небольшая облачность", result["weather"])
    }

    @Test
    fun `news buildItem формирует карточку истории`() {
        val json = """
            {
              "id": 12345678,
              "title": "Show HN: My MCP server",
              "by": "alice",
              "score": 120,
              "descendants": 45,
              "url": "https://example.com",
              "time": 1716163200
            }
        """.trimIndent()

        val result = NewsTool(webClient = anyWebClient()).buildItem(mapper.readTree(json))

        assertEquals("Show HN: My MCP server", result["title"])
        assertEquals("alice", result["author"])
        assertEquals(120, result["score"])
        assertEquals("https://example.com", result["url"])
        assertTrue((result["time_iso"] as String).startsWith("2024-05-20"))
    }

    private fun anyWebClient(): org.springframework.web.reactive.function.client.WebClient =
        org.springframework.web.reactive.function.client.WebClient.builder().build()
}
