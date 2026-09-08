package com.example.llmagent.agent.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalculatorTest {

    private val tool = CalculatorTool()

    private fun calc(expr: String) = runBlocking { tool.execute(mapOf("expression" to expr)) }

    @Test
    fun `basic arithmetic is evaluated`() {
        assertEquals("4", calc("2+2").result)
        assertEquals("8", calc("2+2*3").result)
        assertEquals("20", calc("(2+3)*4").result)
        assertEquals("7", calc("10-3").result)
        assertEquals("6", calc("12/2").result)
        assertEquals("5", calc("10 / 2").result)
        assertEquals("2.5", calc("5/2").result)
        assertEquals("-4", calc("-2*2").result)
        assertEquals("7", calc("1 + (2 + 4)").result)
    }

    @Test
    fun `invalid input returns error instead of executing code`() {
        assertTrue(calc("abc").isError)
        assertTrue(calc("7/0").isError)
        assertTrue(calc("").isError)
        assertTrue(calc("2+").isError)
        assertTrue(calc("3*)").isError)
        assertTrue(calc("__import__('os')").isError)
        assertTrue(calc("2; rm -rf /").isError)
    }

    @Test
    fun `missing expression argument returns error`() {
        val r = runBlocking { tool.execute(emptyMap()) }
        assertTrue(r.isError)
    }

    @Test
    fun `success result is not an error`() {
        val r = calc("1+1")
        assertFalse(r.isError)
    }
}
