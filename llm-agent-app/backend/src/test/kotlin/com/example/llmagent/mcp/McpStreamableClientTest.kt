package com.example.llmagent.mcp

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты MCP Streamable-HTTP клиента поверх MockWebServer: рукопожатие
 * (initialize + notifications/initialized), чтение Mcp-Session-Id из заголовка,
 * tools/list (в т.ч. в SSE-форме `data:`), tools/call (объединение text-контента).
 */
class McpStreamableClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: McpStreamableClient

    private fun initializeJson(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setHeader("Mcp-Session-Id", "sess-123")
        .setBody(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}," +
                "\"serverInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}"
        )

    private fun notificationsAccepted(): MockResponse = MockResponse().setResponseCode(202)

    private fun toolsListJson(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[" +
                "{\"name\":\"get_weather\",\"description\":\"Get current weather\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}," +
                "{\"name\":\"get_time\",\"description\":\"Get time\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        )

    private fun toolsCallJson(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[" +
                "{\"type\":\"text\",\"text\":\"sunny\"}," +
                "{\"type\":\"text\",\"text\":\" in Moscow\"}],\"isError\":false}}"
        )

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = McpStreamableClient(server.url("/mcp").toString())
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `initialize returns true and captures session id`() = runBlocking {
        server.enqueue(initializeJson())
        server.enqueue(notificationsAccepted())

        assertTrue(client.initialize(), "initialize должен вернуть true")

        val initReq = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(initReq.body.readUtf8().contains("\"method\":\"initialize\""), "body содержит method initialize")
        assertTrue(initReq.getHeader("Mcp-Session-Id").isNullOrEmpty(), "до рукопожатия заголовка сессии нет")

        val notifReq = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(notifReq.body.readUtf8().contains("notifications/initialized"), "отправлен notifications/initialized")
    }

    @Test
    fun `listTools returns tools and callTool joins text content`() = runBlocking {
        server.enqueue(initializeJson())
        server.enqueue(notificationsAccepted())
        server.enqueue(toolsListJson())
        server.enqueue(toolsCallJson())

        val tools = client.listTools()
        assertEquals(2, tools.size, "список инструментов прочитан")
        assertEquals("get_weather", tools[0].name)
        assertEquals("Get current weather", tools[0].description)
        assertEquals("get_time", tools[1].name)
        assertTrue(tools[0].inputSchema.path("properties").has("city"), "inputSchema разобран")

        val result = client.callTool("get_weather", mapOf("city" to "Moscow"))
        assertFalse(result.isError, "успешный вызов не является ошибкой")
        assertEquals("sunny in Moscow", result.text, "text-контента объединены")

        // Заголовок Mcp-Session-Id обязан отправляться в последующих запросах.
        // Порядок: initialize, notifications, tools/list, tools/call.
        server.takeRequest(5, TimeUnit.SECONDS)!! // initialize
        server.takeRequest(5, TimeUnit.SECONDS)!! // notifications
        val listReq = server.takeRequest(5, TimeUnit.SECONDS)!!
        val callReq = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("sess-123", listReq.getHeader("Mcp-Session-Id"), "tools/list несёт сессию")
        assertEquals("sess-123", callReq.getHeader("Mcp-Session-Id"), "tools/call несёт сессию")
        assertTrue(callReq.body.readUtf8().contains("get_weather"), "tools/call просит get_weather")
    }

    @Test
    fun `listTools parses sse data line`() = runBlocking {
        server.enqueue(initializeJson())
        server.enqueue(notificationsAccepted())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"foo\",\"description\":\"desc\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n"
                )
        )

        val tools = client.listTools()
        assertEquals(1, tools.size, "SSE-тело с data: разобрано")
        assertEquals("foo", tools[0].name)
        assertEquals("desc", tools[0].description)
    }

    @Test
    fun `callTool returns error result when result has isError true`() = runBlocking {
        server.enqueue(initializeJson())
        server.enqueue(notificationsAccepted())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"bad input\"}],\"isError\":true}}"
                )
        )

        val result = client.callTool("boom", mapOf("x" to 1))
        assertTrue(result.isError, "isError=true — это ошибка")
        assertEquals("bad input", result.text)
    }
}
