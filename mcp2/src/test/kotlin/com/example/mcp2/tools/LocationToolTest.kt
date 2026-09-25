package com.example.mcp2.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты чистой логики определения города по IP — без обращения к сети.
 * Проверяем парсинг ответа ip-api.com в карту результата инструмента get_user_city
 * (успех с city/country/lat/lon и отказ/неквалифицированный ввод -> карта ошибки).
 */
class LocationToolTest {

    @Test
    fun `parse успешного ответа ip-api возвращает город страну регион и координаты`() {
        val json = """
            {
              "status": "success",
              "country": "Russia",
              "countryCode": "RU",
              "region": "MOW",
              "regionName": "Moscow",
              "city": "Moscow",
              "zip": "101000",
              "lat": 55.7522,
              "lon": 37.6156,
              "timezone": "Europe/Moscow",
              "isp": "Example ISP",
              "org": "Example Org",
              "as": "AS1234",
              "query": "77.88.55.55"
            }
        """.trimIndent()

        val result = LocationTool.parse(json)

        assertEquals(true, result["result"])
        assertEquals("Moscow", result["city"])
        assertEquals("Russia", result["country"])
        assertEquals("MOW", result["region"])
        assertEquals(55.7522, (result["lat"] as Double), 1e-6)
        assertEquals(37.6156, (result["lon"] as Double), 1e-6)
        assertEquals("77.88.55.55", result["ip"])
        assertNull(result["error"], "при успехе не должно быть error")
    }

    @Test
    fun `parse ответа со status fail возвращает карту ошибки`() {
        val json = """{ "status": "fail", "message": "reserved range" }""".trimIndent()

        val result = LocationTool.parse(json)

        assertEquals("Не удалось определить город по IP", result["error"])
        assertNotNull(result["message"])
        assertNull(result["result"], "при ошибке не должно быть поля result")
    }

    @Test
    fun `parse ответа с result false возвращает карту ошибки`() {
        val json = """{ "result": false, "message": "not found" }""".trimIndent()

        val result = LocationTool.parse(json)

        assertEquals("Не удалось определить город по IP", result["error"])
        assertNotNull(result["message"])
    }

    @Test
    fun `parse неквалифицированного JSON возвращает карту ошибки вместо исключения`() {
        val result = LocationTool.parse("{ not valid json ")

        assertEquals("Не удалось определить город по IP", result["error"])
        assertNotNull(result["message"])
        assertTrue((result["message"] as String).isNotEmpty())
    }
}
