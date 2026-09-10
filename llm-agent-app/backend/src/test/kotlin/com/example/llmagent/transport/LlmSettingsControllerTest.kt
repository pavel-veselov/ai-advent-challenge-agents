package com.example.llmagent.transport

import com.example.llmagent.config.LlmCatalog
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест GET/PUT /api/llm-settings: динамическая смена настроек без рестарта,
 * вывод contextLimit по каталогу, неизменяемость provider, валидация (HTTP 400).
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LlmSettingsControllerTest {

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

    private val om = ObjectMapper()

    @Test
    fun `GET returns applied settings including derived contextLimit`() {
        val body = getSettings()
        assertTrue(body.hasNonNull("provider"), "нет provider: $body")
        assertTrue(body.hasNonNull("model"), "нет model: $body")
        assertTrue(body.hasNonNull("contextLimit"), "нет contextLimit: $body")
        assertTrue(body.has("temperature"), "нет temperature: $body")
        assertTrue(body.has("maxTokens"), "нет maxTokens: $body")
        assertTrue(body.has("topP"), "нет topP: $body")
        assertTrue(body.has("timeoutSeconds"), "нет timeoutSeconds: $body")
        assertTrue(body.has("tools"), "нет tools: $body")

        // contextLimit согласован с выбранной моделью по каталогу
        val model = body["model"].asText()
        val derived = LlmCatalog.contextLimit(model)
        if (derived != null) {
            assertEquals(derived, body["contextLimit"].asInt(), "contextLimit не выведен из модели $model")
        }
    }

    @Test
    fun `PUT updates settings and GET returns them - roundtrip`() {
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"deepseek-v4-flash","temperature":0.2,"maxTokens":512}""")
            .exchange()
            .expectStatus().isOk

        val settings = getSettings()
        assertEquals("deepseek-v4-flash", settings["model"].asText())
        assertEquals(1024 * 1024, settings["contextLimit"].asInt(), "contextLimit по каталогу для deepseek-v4-flash")
        assertEquals(0.2, settings["temperature"].asDouble(), 1e-9)
        assertEquals(512, settings["maxTokens"].asInt())
    }

    @Test
    fun `changing model derives new contextLimit per catalog`() {
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"qwen3.8-27b"}""")
            .exchange()
            .expectStatus().isOk
        val qwen = getSettings()
        assertEquals(198 * 1024, qwen["contextLimit"].asInt())

        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"glm-5.3-flash"}""")
            .exchange()
            .expectStatus().isOk
        val glm = getSettings()
        assertEquals(256 * 1024, glm["contextLimit"].asInt())
    }

    @Test
    fun `provider changes are ignored and current provider kept`() {
        val before = getSettings()["provider"].asText()

        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"provider":"gpustack","temperature":0.5}""")
            .exchange()
            .expectStatus().isOk

        val after = getSettings()
        assertEquals(before, after["provider"].asText(), "provider не должен меняться через PUT")
        assertEquals(0.5, after["temperature"].asDouble(), 1e-9, "остальные поля должны примениться")
    }

    @Test
    fun `PUT with unknown model returns 400 and does not change model`() {
        val before = getSettings()["model"].asText()

        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"no_such_model"}""")
            .exchange()
            .expectStatus().isBadRequest

        assertEquals(before, getSettings()["model"].asText(), "модель не должна измениться после 400")
    }

    @Test
    fun `PUT with negative temperature returns 400`() {
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"temperature":-0.5}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `PUT with non positive maxTokens returns 400`() {
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"maxTokens":0}""")
            .exchange()
            .expectStatus().isBadRequest

        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"maxTokens":-5}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `PUT returns updated settings in response`() {
        val response = client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"qwen3.8-27b","timeoutSeconds":99}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val body = om.readTree(response)
        assertEquals("qwen3.8-27b", body["model"].asText())
        assertEquals(99, body["timeoutSeconds"].asLong())
        assertEquals(198 * 1024, body["contextLimit"].asInt())
    }

    @Test
    fun `GET returns reasoningEnabled`() {
        val body = getSettings()
        assertTrue(body.has("reasoningEnabled"), "нет reasoningEnabled: $body")
        assertTrue(body["reasoningEnabled"].isBoolean, "reasoningEnabled должен быть boolean: $body")
    }

    @Test
    fun `PUT reasoningEnabled false roundtrip`() {
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"reasoningEnabled":false}""")
            .exchange()
            .expectStatus().isOk
        val settings = getSettings()
        assertEquals(false, settings["reasoningEnabled"].asBoolean())
    }

    @Test
    fun `PUT reasoningEnabled null resets to default true`() {
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"reasoningEnabled":false}""")
            .exchange()
            .expectStatus().isOk
        assertEquals(false, getSettings()["reasoningEnabled"].asBoolean())

        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"reasoningEnabled":null}""")
            .exchange()
            .expectStatus().isOk
        assertEquals(true, getSettings()["reasoningEnabled"].asBoolean(), "null сбрасывает к дефолту true")
    }

    @Test
    fun `PUT without reasoningEnabled leaves it unchanged`() {
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"reasoningEnabled":false,"temperature":0.4}""")
            .exchange()
            .expectStatus().isOk
        assertEquals(false, getSettings()["reasoningEnabled"].asBoolean())

        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"temperature":0.8}""")
            .exchange()
            .expectStatus().isOk
        assertEquals(0.8, getSettings()["temperature"].asDouble(), 1e-9)
        assertEquals(false, getSettings()["reasoningEnabled"].asBoolean(), "отсутствие поля не меняет значение")
    }

    private fun getSettings(): JsonNode {
        val body = client.get().uri("/api/llm-settings")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }
}
