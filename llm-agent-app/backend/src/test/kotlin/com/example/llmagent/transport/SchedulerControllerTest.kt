package com.example.llmagent.transport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест REST панели планировщика (Day-17): все 5 эндпоинтов проксируют
 * вызовы к MCP papkin-helper и fail-open'ят в 502 при недоступном сервере; GET /tasks
 * на сконфигурированном сервере (MockWebServer) отдаёт распарсенный JSON.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulerControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-sched-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val om = ObjectMapper()
    private lateinit var mock: MockWebServer

    @BeforeEach
    fun setup() {
        // Тесты одного класса делят один Spring-контекст и один temp SQLite-файл.
        try {
            jdbc.update("DELETE FROM mcp_servers")
        } catch (_: Exception) {
        }
    }

    @AfterEach
    fun teardown() {
        if (::mock.isInitialized) mock.shutdown()
    }

    private fun initializeJson(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setHeader("Mcp-Session-Id", "sess-1")
        .setBody(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},""" +
                """"serverInfo":{"name":"papkin-helper","version":"1.0"}}}"""
        )

    private fun notificationsAccepted(): MockResponse = MockResponse().setResponseCode(202)

    private fun toolsCallJson(text: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":${om.writeValueAsString(text)}}],"isError":false}}"""
        )

    @Test
    fun `all endpoints return 502 with error json when MCP server is not configured`() {
        // GET /tasks
        var body = client.get().uri("/api/scheduler/tasks")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(body.contains("недоступен"), "GET 502: $body")

        // POST /tasks
        body = client.post().uri("/api/scheduler/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"job","source":"run","interval_seconds":300}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(body.contains("недоступен"), "POST 502: $body")

        // PATCH /tasks/{id}/interval
        body = client.patch().uri("/api/scheduler/tasks/123/interval")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"interval_seconds":60}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(body.contains("недоступен"), "PATCH 502: $body")

        // DELETE /tasks/{id}
        body = client.delete().uri("/api/scheduler/tasks/123")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(body.contains("недоступен"), "DELETE 502: $body")

        // POST /summary
        body = client.post().uri("/api/scheduler/summary")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"task_id":"t1","since_hours":24}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(body.contains("недоступен"), "summary 502: $body")
    }

    @Test
    fun `GET tasks proxies via MCP and returns parsed json`() {
        mock = MockWebServer()
        mock.start()
        val mcpUrl = mock.url("/mcp").toString()

        // Включённый сервер papkin-helper на адрес мок-сервера.
        jdbc.update(
            "INSERT INTO mcp_servers (name, url, enabled, created_at, updated_at) VALUES (?, ?, 1, ?, ?)",
            "papkin-helper", mcpUrl, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
        )

        // Порядок: initialize -> notifications/initialized -> tools/call scheduler_list_tasks.
        mock.enqueue(initializeJson())
        mock.enqueue(notificationsAccepted())
        // Реальный papkin-helper отдаёт camelCase поля — контроллер обязан конвертировать в snake_case.
        mock.enqueue(toolsCallJson(
            """[{"id":101,"name":"job","source":"weather","intervalSeconds":300,"active":true,"lastRunAt":null,"runsCount":7}]""",
        ))

        val body = client.get().uri("/api/scheduler/tasks")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!

        val node: JsonNode = om.readTree(body)
        assertTrue(node.isArray, "список задач — JSON-массив")
        assertEquals(1, node.size())
        assertEquals("job", node[0]["name"].asText())
        assertEquals(101, node[0]["id"].asInt())
        assertEquals(300, node[0]["interval_seconds"].asInt())
        assertEquals(true, node[0]["active"].asBoolean())
        assertEquals(7, node[0]["runs_count"].asInt())
        assertTrue(node[0].has("last_run_at"), "camelCase lastRunAt должен стать last_run_at")
    }

    @Test
    fun `POST summary passes task_id and since_hours to MCP and returns parsed json`() {
        mock = MockWebServer()
        mock.start()
        val mcpUrl = mock.url("/mcp").toString()

        jdbc.update(
            "INSERT INTO mcp_servers (name, url, enabled, created_at, updated_at) VALUES (?, ?, 1, ?, ?)",
            "papkin-helper", mcpUrl, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
        )

        mock.enqueue(initializeJson())
        mock.enqueue(notificationsAccepted())
        // Реальный papkin-helper сводка: camelCase поля + snake_case внутри (ratesList/change_from_previous).
        mock.enqueue(toolsCallJson(
            """{"taskId":101,"source":"weather","count":2,"since":24,"min":12.5,"max":20.0,"avg":16.25,"latest":18.0}""",
        ))

        val body = client.post().uri("/api/scheduler/summary")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"task_id":"101","since_hours":24}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!

        val node: JsonNode = om.readTree(body)
        assertEquals(101, node["task_id"].asInt())
        assertEquals(2, node["count"].asInt())
        assertEquals(24, node["since"].asInt())
        assertEquals(12.5, node["min"].asDouble())
        assertEquals(16.25, node["avg"].asDouble())
        assertEquals(18.0, node["latest"].asDouble())
        assertTrue(node.has("task_id"), "camelCase taskId должен стать task_id")

        // Проверяем, что snake_case из тела фронтенда дошли до MCP как camelCase аргументы.
        mock.takeRequest(5, TimeUnit.SECONDS)!! // initialize
        mock.takeRequest(5, TimeUnit.SECONDS)!! // notifications
        val callBody = mock.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue(callBody.contains("\"name\":\"scheduler_summary\""), "tools/call просит scheduler_summary")
        assertTrue(callBody.contains("\"taskId\":101"), "taskId (camelCase) ушёл в аргументы MCP")
        assertTrue(callBody.contains("\"sinceHours\":24"), "sinceHours (camelCase) ушёл в аргументы MCP")
        assertTrue(!callBody.contains("\"task_id\""), "snake_case task_id не должен уходить в аргументы MCP")
    }
}
