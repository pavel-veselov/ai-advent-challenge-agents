package com.example.llmagent.agent.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class GetCurrentDateTimeTest {

    @Test
    fun `returns parseable utc datetime`() {
        val r = runBlocking { GetCurrentDateTimeTool().execute(emptyMap()) }
        assertFalse(r.isError, "результат не должен быть ошибкой: ${r.result}")
        val parsed = OffsetDateTime.parse(r.result)
        assertEquals(0, parsed.offset.totalSeconds)
    }

    @Test
    fun `ignores arguments`() {
        val r = runBlocking { GetCurrentDateTimeTool().execute(mapOf("foo" to "bar")) }
        assertFalse(r.isError)
        assertTrue(r.result.isNotBlank())
    }
}
