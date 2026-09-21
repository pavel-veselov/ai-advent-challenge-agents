package com.example.llmagent.agent

import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Фейковый инструмент для проверки динамической регистрации в [ToolRegistry]. */
private class FakeTool(name: String) : Tool {
    override val name: String = name
    override val description: String = "fake $name"
    override val parameters: JsonNode = ObjectMapper().createObjectNode()
    override suspend fun execute(args: Map<String, Any?>): ToolResult = ToolResult("ok-$name")
}

/**
 * Юнит-тесты динамического реестра инструментов (Day-16): встроенные инструменты
 * остаются на месте при пустом dynamic, регистрация/удаление MCP-инструментов
 * отражается в definitions/names/get, масcовое удаление через unregisterAll.
 */
class ToolRegistryTest {

    private fun registry(): ToolRegistry =
        ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))

    @Test
    fun `startup with empty dynamic equals built-in set`() {
        val r = registry()
        assertTrue(r.names().containsAll(listOf("calculator", "get_current_datetime")), "встроенные инструменты на месте")
        assertEquals(2, r.names().size, "при старте (пустой dynamic) — только встроенные")
        assertEquals(2, r.definitions().size)
        assertNotNull(r.get("calculator"))
        assertNotNull(r.get("get_current_datetime"))
        assertTrue(r.dynamicNames().isEmpty(), "dynamic пуст при старте")
    }

    @Test
    fun `register adds fixture to definitions names and get`() {
        val r = registry()
        r.register(FakeTool("get_weather"))

        assertTrue(r.names().contains("get_weather"), "имя появилось в names")
        assertTrue(r.dynamicNames().contains("get_weather"), "имя в dynamic")
        val def = r.definitions().first { it.name == "get_weather" }
        assertEquals("fake get_weather", def.description)
        assertNotNull(r.get("get_weather"), "get находит зарегистрированный инструмент")
        // встроенные не тронуты
        assertNotNull(r.get("calculator"))
        assertEquals(3, r.names().size)
    }

    @Test
    fun `register overwrites same name and unregister removes`() {
        val r = registry()
        r.register(FakeTool("shared"))
        assertEquals("fake shared", r.get("shared")!!.description)

        r.register(FakeTool("shared"))
        r.unregister("shared")
        assertNull(r.get("shared"), "после unregister инструмент исчезает")
        assertFalse(r.names().contains("shared"))
        // встроенные всё ещё есть
        assertTrue(r.names().contains("calculator"))
        assertTrue(r.names().contains("get_current_datetime"))
    }

    @Test
    fun `unregisterAll removes many dynamic leaving built-ins`() {
        val r = registry()
        r.register(FakeTool("a"))
        r.register(FakeTool("b"))
        r.register(FakeTool("c"))

        r.unregisterAll(listOf("a", "b"))
        assertEquals(setOf("c"), r.dynamicNames(), "a и b сняты, c остался")
        assertFalse(r.names().contains("a"))
        assertTrue(r.names().contains("calculator"), "встроенные на месте")
    }
}
