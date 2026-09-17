package com.example.llmagent.transport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест GET/PUT /api/workflow-settings (Day-14, human-in-the-loop воркфлоу):
 * дефолты (enabled=false, mode=manual), сохранение обоих ключей, валидация mode (400) и
 * переживание перезапуска (полагаемся на app_settings). Отдельный временный SQLite-файл.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkflowSettingsControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-wf-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    private val om = ObjectMapper()

    private fun get(): JsonNode =
        om.readTree(
            client.get().uri("/api/workflow-settings")
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        )

    private fun put(json: String, expectStatus: Int = 200): JsonNode {
        val spec = client.put().uri("/api/workflow-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
        val response = if (expectStatus == 200) spec.expectStatus().isOk else spec.expectStatus().isBadRequest
        val body = response
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
        return om.readTree(body!!)
    }

    @Test
    fun `GET returns defaults disabled and manual`() {
        val node = get()
        assertFalse(node["enabled"].asBoolean(), "воркфлоу по умолчанию выключен")
        assertEquals("manual", node["mode"].asText())
    }

    @Test
    fun `PUT saves both keys and roundtrips`() {
        val saved = put("""{"enabled":true,"mode":"auto"}""")
        assertTrue(saved["enabled"].asBoolean())
        assertEquals("auto", saved["mode"].asText())
        // GET подтверждает сохранённое
        val node = get()
        assertTrue(node["enabled"].asBoolean())
        assertEquals("auto", node["mode"].asText())
    }

    @Test
    fun `PUT invalid mode rejected with 400 and state unchanged`() {
        // предварительно выключаем воркфлоу
        put("""{"enabled":false,"mode":"manual"}""")
        put("""{"enabled":true,"mode":"похуй"}""", expectStatus = 400)
        val node = get()
        assertFalse(node["enabled"].asBoolean(), "после 400 настройки не должны измениться")
        assertEquals("manual", node["mode"].asText())
    }

    @Test
    fun `PUT missing fields rejected with 400`() {
        put("""{"mode":"auto"}""", expectStatus = 400)
        put("""{"enabled":true}""", expectStatus = 400)
    }
}
