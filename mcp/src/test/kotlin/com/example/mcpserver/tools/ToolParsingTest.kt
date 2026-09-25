package com.example.mcpserver.tools

import com.example.mcpserver.collector.HttpSourceCollector
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

        val result = HttpSourceCollector.parseRates(mapper.readTree(json), "USD")

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

        val result = HttpSourceCollector.parseRates(mapper.readTree(json), null)
        @Suppress("UNCHECKED_CAST")
        val rates = result["rates"] as Map<String, Any>
        assertEquals(2, rates.size)
    }

    @Test
    fun `weather buildWeather собирает текущую погоду`() {
        val json = """
            {
              "type": "Feature",
              "geometry": { "coordinates": [37.6173, 55.7558, 150] },
              "properties": {
                "meta": { "units": { "air_temperature": "celsius", "wind_speed": "m/s" } },
                "timeseries": [
                  {
                    "time": "2026-09-25T13:00:00Z",
                    "data": {
                      "instant": {
                        "details": {
                          "air_temperature": 12.6,
                          "wind_speed": 2.2,
                          "cloud_area_fraction": 100.0,
                          "relative_humidity": 81.5,
                          "wind_from_direction": 175.0,
                          "air_pressure_at_sea_level": 1022.2
                        }
                      },
                      "next_1_hours": { "summary": { "symbol_code": "partlycloudy_day" } }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = HttpSourceCollector.buildWeather(mapper.readTree(json), "celsius", "Москва", "Россия")

        assertEquals("Москва", result["city"])
        assertEquals("Россия", result["country"])
        assertEquals(12.6, result["temperature"])
        assertEquals("°C", result["temperature_unit"])
        assertEquals(2.2, result["wind_speed"])
        assertEquals("m/s", result["wind_speed_unit"])
        assertEquals("Переменная облачность", result["weather"])
        assertEquals("2026-09-25T13:00:00Z", result["time"])
        assertEquals("met.no", result["source"])
    }

    @Test
    fun `weather buildWeather конвертирует фаренгейты из цельсиев`() {
        val json = """
            {
              "properties": {
                "timeseries": [
                  {
                    "time": "2026-09-25T13:00:00Z",
                    "data": {
                      "instant": { "details": { "air_temperature": 12.6, "wind_speed": 2.2 } },
                      "next_1_hours": { "summary": { "symbol_code": "lightrain" } }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = HttpSourceCollector.buildWeather(mapper.readTree(json), "fahrenheit", "London", "Великобритания")

        assertEquals(54.68, result["temperature"]) // 12.6 °C -> 54.68 °F
        assertEquals("°F", result["temperature_unit"])
        assertEquals("Небольшой дождь", result["weather"])
        assertEquals("met.no", result["source"])
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

        val result = HttpSourceCollector.buildItem(mapper.readTree(json))

        assertEquals("Show HN: My MCP server", result["title"])
        assertEquals("alice", result["author"])
        assertEquals(120, result["score"])
        assertEquals("https://example.com", result["url"])
        assertTrue((result["time_iso"] as String).startsWith("2024-05-20"))
    }
}
