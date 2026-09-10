package com.example.llmagent.transport

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
 * Интеграционный тест каталога моделей: GET /api/models (список с описаниями и состоянием),
 * PUT /api/models/{id}/enabled (вкл/выкл, персистентность в SQLite), защита активной модели,
 * и связка с PUT /api/llm-settings (отключённая модель больше не выбирается).
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelsControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-models-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    private val om = ObjectMapper()

    @Test
    fun `GET returns 3 catalog models with descriptions and enabled defaults true`() {
        val models = models()["models"]
        assertTrue(models.isArray, "models должен быть массивом")
        assertEquals(3, models.size())

        val expectedIds = listOf("qwen3.8-27b", "deepseek-v4-flash", "glm-5.3-flash")
        val expectedDescriptions = listOf(
            "Универсальная чат-модель с окном 202 752 токена. Режим рассуждений включается и отключается в настройках.",
            "Быстрая и экономичная чат-модель. Режим рассуждений включается и отключается в настройках.",
            "Чат-модель с принудительным режимом рассуждений — шлюз не позволяет его отключить.",
        )
        expectedIds.forEachIndexed { i, id ->
            val m = models[i]
            assertEquals(id, m["id"].asText(), "порядок/итент модели #$i")
            assertEquals(expectedDescriptions[i], m["description"].asText(), "описание модели $id")
            assertTrue(m["enabled"].asBoolean(), "модель $id должна быть включена по умолчанию")
        }
    }

    @Test
    fun `PUT toggles enabled state and GET reflects it - roundtrip`() {
        // выключить
        val off = toggle("deepseek-v4-flash", false)
        assertEquals("deepseek-v4-flash", off["id"].asText())
        assertEquals(false, off["enabled"].asBoolean())
        assertEquals(false, enabledOf("deepseek-v4-flash"))
        assertEquals(true, enabledOf("qwen3.8-27b"), "остальные модели не должны меняться")

        // вернуть обратно
        val on = toggle("deepseek-v4-flash", true)
        assertEquals("deepseek-v4-flash", on["id"].asText())
        assertEquals(true, on["enabled"].asBoolean())
        assertEquals(true, enabledOf("deepseek-v4-flash"))
    }

    @Test
    fun `PUT with unknown model id returns 404`() {
        client.put().uri("/api/models/no_such_model/enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"enabled":false}""")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `PUT with non boolean enabled returns 400`() {
        client.put().uri("/api/models/deepseek-v4-flash/enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"enabled":"yes"}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `PUT disabling currently active model returns 400 and keeps it enabled`() {
        setActive("qwen3.8-27b")

        val body = client.put().uri("/api/models/qwen3.8-27b/enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"enabled":false}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("Нельзя отключить активную модель"), "сообщение 400: $body")

        assertEquals(true, enabledOf("qwen3.8-27b"), "модель должна остаться включённой после 400")
    }

    @Test
    fun `PUT llm-settings with disabled model returns 400 mentioning otklyuchena`() {
        setActive("qwen3.8-27b")
        toggle("deepseek-v4-flash", false)

        val body = client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"deepseek-v4-flash"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("отключена в каталоге"), "сообщение 400: $body")

        // активная модель не поменялась
        assertEquals("qwen3.8-27b", getSettings()["model"].asText())

        // cleanup — вернуть deepseek, чтобы не влиять на другие тесты класса
        toggle("deepseek-v4-flash", true)
    }

    @Test
    fun `disabled model is hidden from llm-settings selection but stays in catalog`() {
        setActive("qwen3.8-27b")
        toggle("glm-5.3-flash", false)
        try {
            // в каталоге модель по-прежнему есть (только выключена)
            assertEquals(false, enabledOf("glm-5.3-flash"))
            // выбрать её больше нельзя
            val body = client.put().uri("/api/llm-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"model":"glm-5.3-flash"}""")
                .exchange()
                .expectStatus().isBadRequest
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
            assertTrue(body.contains("отключена"), "сообщение 400: $body")
        } finally {
            toggle("glm-5.3-flash", true)
        }
    }

    private fun models(): JsonNode {
        val body = client.get().uri("/api/models")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun enabledOf(id: String): Boolean =
        models()["models"].map { it["id"].asText() to it["enabled"].asBoolean() }.toMap()[id]
            ?: error("модель $id не найдена в каталоге")

    private fun toggle(id: String, enabled: Boolean): JsonNode {
        val body = client.put().uri("/api/models/$id/enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"enabled":$enabled}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun setActive(model: String) {
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"$model"}""")
            .exchange()
            .expectStatus().isOk
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
