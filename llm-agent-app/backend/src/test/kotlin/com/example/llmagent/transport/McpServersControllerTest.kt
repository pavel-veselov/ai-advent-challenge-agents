package com.example.llmagent.transport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * Интеграционный тест REST управления MCP-серверами (Day-16): GET (список, всегда 200),
 * POST (201, enabled=false; blank → 400; дубликат имени → 409), PUT /{id}/enabled
 * (вкл/выкл; активация инструментов fail-open — с недоступным URL инструментов нет),
 * PUT /{id} (изменение name/url; 400 blank; 409 дубликат имени; 404 — нет сервера),
 * DELETE (deleted:true; 404 для несуществующего).
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServersControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-mcp-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val om = ObjectMapper()

    // Порт 1 закрыт — активация недоступного сервера завершается fail-open мгновенно.
    private val badUrl = "http://127.0.0.1:1/"

    @BeforeEach
    fun cleanDb() {
        // Тесты одного класса делят один Spring-контекст и один temp SQLite-файл;
        // чистим mcp_servers, чтобы каждый тест стартовал с пустого состояния.
        try {
            jdbc.update("DELETE FROM mcp_servers")
        } catch (_: Exception) {
        }
    }

    private fun getList(): JsonNode {
        val body = client.get().uri("/api/mcp-servers")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun postServer(name: String, url: String): JsonNode {
        val body = client.post().uri("/api/mcp-servers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"$name","url":"$url"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun putEnabled(id: Long, enabled: Boolean): JsonNode {
        val body = client.put().uri("/api/mcp-servers/$id/enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"enabled":$enabled}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun putEdit(id: Long, name: String, url: String): JsonNode {
        val body = client.put().uri("/api/mcp-servers/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"$name","url":"$url"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    @Test
    fun `GET returns empty list initially`() {
        val arr = getList()
        assertTrue(arr.isArray, "список — массив")
        assertEquals(0, arr.size())
    }

    @Test
    fun `POST adds server with enabled false and empty tools`() {
        val dto = postServer("weather", "http://localhost:9000/mcp")
        assertTrue(dto["id"].asLong() > 0, "сервер получил id")
        assertEquals("weather", dto["name"].asText())
        assertEquals("http://localhost:9000/mcp", dto["url"].asText())
        assertFalse(dto["enabled"].asBoolean(), "новый сервер выключен")
        assertTrue(dto["tools"].isArray && dto["tools"].size() == 0, "инструментов пока нет")
        assertTrue(dto["createdAt"].asText().isNotBlank())
        assertTrue(dto["updatedAt"].asText().isNotBlank())

        // тот же сервер виден в GET
        val list = getList()
        assertEquals(1, list.size())
        assertEquals("weather", list[0]["name"].asText())
    }

    @Test
    fun `POST blank name or url returns 400`() {
        val body = client.post().uri("/api/mcp-servers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"   ","url":"http://x"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("name и url"), "сообщение 400: $body")

        // пустой url
        val body2 = client.post().uri("/api/mcp-servers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"name\":\"x\",\"url\":\"\"}")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body2.contains("name и url"), "сообщение 400: $body2")
    }

    @Test
    fun `POST duplicate name returns 409`() {
        postServer("weather", "http://a")
        val body = client.post().uri("/api/mcp-servers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"weather","url":"http://b"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("уже существует"), "сообщение 409: $body")
    }

    @Test
    fun `PUT enabled true activates server fail-open and reflects flag`() {
        val added = postServer("weather", badUrl)
        val id = added["id"].asLong()

        val dto = putEnabled(id, true)
        assertTrue(dto["enabled"].asBoolean(), "сервер включён")
        assertTrue(dto["tools"].isArray, "активация fail-open — инструментов нет, но ответ корректен")

        // состояние персистится в GET
        val list = getList()
        assertTrue(list[0]["enabled"].asBoolean(), "флаг виден в списке")
    }

    @Test
    fun `PUT enabled false deactivates`() {
        val added = postServer("weather", badUrl)
        val id = added["id"].asLong()
        putEnabled(id, true)

        val dto = putEnabled(id, false)
        assertFalse(dto["enabled"].asBoolean(), "сервер выключен")
        assertFalse(getList()[0]["enabled"].asBoolean())
    }

    @Test
    fun `PUT on nonexistent id returns 404`() {
        val body = client.put().uri("/api/mcp-servers/424242/enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"enabled":true}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("не найден"), "404: $body")
    }

    @Test
    fun `DELETE removes server and second delete returns 404`() {
        val added = postServer("weather", badUrl)
        val id = added["id"].asLong()

        val body = client.delete().uri("/api/mcp-servers/$id")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("\"deleted\":true") || body.contains("deleted"), "deleted:true — $body")
        assertTrue(getList().isEmpty(), "после удаления список пуст")

        client.delete().uri("/api/mcp-servers/$id")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `DELETE on nonexistent id returns 404`() {
        client.delete().uri("/api/mcp-servers/424242")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `PUT edit updates name and url`() {
        val added = postServer("weather", badUrl)
        val id = added["id"].asLong()

        val dto = putEdit(id, "weather2", "http://localhost:9001/mcp")
        assertEquals("weather2", dto["name"].asText())
        assertEquals("http://localhost:9001/mcp", dto["url"].asText())
        assertTrue(dto["tools"].isArray, "tools присутствуют в ответе")
        assertTrue(dto["updatedAt"].asText().isNotBlank())

        // изменения персистятся в GET
        val list = getList()
        assertEquals(1, list.size())
        assertEquals("weather2", list[0]["name"].asText())
        assertEquals("http://localhost:9001/mcp", list[0]["url"].asText())
    }

    @Test
    fun `PUT edit on enabled server keeps flag and reactivates fail-open`() {
        val added = postServer("weather", badUrl)
        val id = added["id"].asLong()
        putEnabled(id, true)

        val dto = putEdit(id, "weather2", "http://localhost:9001/mcp")
        assertTrue(dto["enabled"].asBoolean(), "сервер остался включён")
        assertTrue(dto["tools"].isArray, "реактивация fail-open — инструментов нет, но ответ корректен")
    }

    @Test
    fun `PUT edit blank name returns 400`() {
        val added = postServer("weather", badUrl)
        val id = added["id"].asLong()
        val body = client.put().uri("/api/mcp-servers/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"   ","url":"http://x"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("name и url"), "сообщение 400: $body")
    }

    @Test
    fun `PUT edit duplicate name on another server returns 409`() {
        postServer("weather", badUrl)
        val other = postServer("other", badUrl)
        val otherId = other["id"].asLong()
        val body = client.put().uri("/api/mcp-servers/$otherId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"weather","url":"http://x"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("уже существует"), "сообщение 409: $body")
    }

    @Test
    fun `PUT edit on nonexistent id returns 404`() {
        val body = client.put().uri("/api/mcp-servers/424242")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"x","url":"http://x"}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("не найден"), "404: $body")
    }
}
