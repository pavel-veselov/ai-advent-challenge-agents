package com.example.llmagent.transport

import com.example.llmagent.agent.SessionCompressionStore
import com.example.llmagent.agent.SessionStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест GET/PUT /api/sessions/{sessionId}/compression: значения по умолчанию,
 * частичное обновление, валидация (400), работа с сессией без сообщений (создана кнопкой «+»), чистка при DELETE.
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompressionControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var sessionStore: SessionStore

    @Autowired
    lateinit var compressionStore: SessionCompressionStore

    private val om = ObjectMapper()

    private fun seedSession(id: String) {
        sessionStore.append(id, "user", "привет")
        sessionStore.append(id, "assistant", "привет!")
    }

    private fun get(id: String): JsonNode {
        val body = client.get().uri("/api/sessions/$id/compression")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun put(id: String, json: String): JsonNode {
        val body = client.put().uri("/api/sessions/$id/compression")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    @Test
    fun `GET returns defaults for existing session`() {
        seedSession("c-default")
        val node = get("c-default")
        assertEquals("c-default", node["sessionId"].asText())
        assertEquals(false, node["enabled"].asBoolean())
        assertEquals(5, node["keepLast"].asInt())
        assertEquals(10, node["summaryEvery"].asInt())
    }

    @Test
    fun `GET returns defaults for session without messages`() {
        // Сессия без сообщений (создана кнопкой «+») — GET отдаёт дефолты, ничего не персистя
        val node = get("no-such-session")
        assertEquals("no-such-session", node["sessionId"].asText())
        assertEquals(false, node["enabled"].asBoolean())
        assertEquals(5, node["keepLast"].asInt())
        assertEquals(10, node["summaryEvery"].asInt())
    }

    @Test
    fun `PUT partial update leaves other fields unchanged`() {
        seedSession("c-partial")
        val response = put("c-partial", """{"keepLast":7}""")
        assertEquals(7, response["keepLast"].asInt())
        assertEquals(false, response["enabled"].asBoolean(), "enabled не должно измениться")
        assertEquals(10, response["summaryEvery"].asInt(), "summaryEvery не должно измениться")

        val after = get("c-partial")
        assertEquals(7, after["keepLast"].asInt())
        assertEquals(10, after["summaryEvery"].asInt(), "summaryEvery должно остаться 10")
    }

    @Test
    fun `PUT full update roundtrips through GET`() {
        seedSession("c-full")
        put("c-full", """{"enabled":true,"keepLast":3,"summaryEvery":7}""")
        val node = get("c-full")
        assertEquals(true, node["enabled"].asBoolean())
        assertEquals(3, node["keepLast"].asInt())
        assertEquals(7, node["summaryEvery"].asInt())
    }

    @Test
    fun `PUT works for session without messages`() {
        // PUT для сессии без сообщений сохраняет настройки — применятся, когда сессия начнёт диалог
        val node = put("ghost", """{"keepLast":3}""")
        assertEquals(3, node["keepLast"].asInt())
    }

    @Test
    fun `PUT validation rejects bad values with 400`() {
        seedSession("c-bad")
        val badBodies = listOf(
            """{"keepLast":0}""",
            """{"keepLast":51}""",
            """{"keepLast":"abc"}""",
            """{"summaryEvery":1}""",
            """{"summaryEvery":101}""",
            """{"enabled":"yes"}""",
            """{"enabled":null}""",
        )
        for (json in badBodies) {
            client.put().uri("/api/sessions/c-bad/compression")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isBadRequest
        }
        // после серии 400 настройки не должны измениться
        val node = get("c-bad")
        assertEquals(false, node["enabled"].asBoolean())
        assertEquals(5, node["keepLast"].asInt())
        assertEquals(10, node["summaryEvery"].asInt())
    }

    @Test
    fun `DELETE session also removes compression data`() {
        seedSession("c-del")
        put("c-del", """{"enabled":true}""")
        assertEquals(true, compressionStore.getSettings("c-del").enabled)

        client.delete().uri("/api/sessions/c-del")
            .exchange()
            .expectStatus().isOk

        assertFalse(compressionStore.getSettings("c-del").enabled, "настройки должны вернуться к дефолту")
        assertNull(compressionStore.getSummary("c-del"))
        // после удаления сессии GET снова отдаёт дефолты (строки нет)
        val node = get("c-del")
        assertEquals("c-del", node["sessionId"].asText())
        assertEquals(5, node["keepLast"].asInt())
    }

    @Test
    fun `PUT accepting boundary values`() {
        seedSession("c-boundary")
        val node = put("c-boundary", """{"keepLast":1,"summaryEvery":2}""")
        assertEquals(1, node["keepLast"].asInt())
        assertEquals(2, node["summaryEvery"].asInt())

        val node2 = put("c-boundary", """{"keepLast":50,"summaryEvery":100}""")
        assertEquals(50, node2["keepLast"].asInt())
        assertEquals(100, node2["summaryEvery"].asInt())
        assertTrue(node2["enabled"].isBoolean)
    }
}
