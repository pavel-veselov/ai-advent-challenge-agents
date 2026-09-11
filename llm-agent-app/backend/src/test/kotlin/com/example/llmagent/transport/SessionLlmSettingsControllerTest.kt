package com.example.llmagent.transport

import com.example.llmagent.agent.SessionStore
import com.example.llmagent.config.SessionLlmSettingsStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест GET/PUT /api/sessions/{sessionId}/llm-settings: fallback на текущие
 * глобальные настройки без персистентности, частичное обновление (сессия A не влияет на B),
 * валидация (400), работа с сессией без сообщений (создана кнопкой «+»), чистка при DELETE.
 *
 * Глобальные дефолты контекста: model=default-coding, maxTokens=10000, reasoningEnabled=true,
 * contextLimit=126608. Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionLlmSettingsControllerTest {

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
    lateinit var llmSettingsStore: SessionLlmSettingsStore

    private val om = ObjectMapper()

    private fun seedSession(id: String) {
        sessionStore.append(id, "user", "привет")
        sessionStore.append(id, "assistant", "привет!")
    }

    private fun get(id: String): JsonNode {
        val body = client.get().uri("/api/sessions/$id/llm-settings")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun put(id: String, json: String): JsonNode {
        val body = client.put().uri("/api/sessions/$id/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun putStatus(id: String, json: String): HttpStatusCode {
        val status = client.put().uri("/api/sessions/$id/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .status
        return status
    }

    @Test
    fun `GET returns current global defaults without persisting`() {
        seedSession("s-global")
        val node = get("s-global")
        // полный эффективный набор — те же поля, что у глобального /api/llm-settings (без provider)
        assertEquals("default-coding", node["model"].asText())
        assertEquals(126608, node["contextLimit"].asInt())
        assertEquals(0.7, node["temperature"].asDouble(), 1e-9)
        assertEquals(1.0, node["topP"].asDouble(), 1e-9)
        assertTrue(node["topK"].isNull, "topK не задан глобально → null в выдаче: $node")
        assertEquals(10000, node["maxTokens"].asInt())
        assertEquals(60, node["timeoutSeconds"].asLong())
        assertEquals(0.1, node["priceInputPer1M"].asDouble(), 1e-9)
        assertEquals(0.1, node["priceOutputPer1M"].asDouble(), 1e-9)
        assertEquals(true, node["reasoningEnabled"].asBoolean())

        // GET не должен создавать строку настроек (fallback без персистентности)
        assertTrue(llmSettingsStore.get("s-global") == null, "GET не должен персистить строку")
    }

    @Test
    fun `PUT model in session A does not affect session B which keeps global defaults`() {
        seedSession("s-a")
        seedSession("s-b")

        put("s-a", """{"model":"qwen3.8-27b"}""")
        val a = get("s-a")
        assertEquals("qwen3.8-27b", a["model"].asText())
        assertEquals(198 * 1024, a["contextLimit"].asInt(), "контекст выведен из qwen3.8-27b")

        // сессия B не тронута — те же глобальные дефолты
        val b = get("s-b")
        assertEquals("default-coding", b["model"].asText())
        assertEquals(126608, b["contextLimit"].asInt())
        assertEquals(10000, b["maxTokens"].asInt())
        assertEquals(true, b["reasoningEnabled"].asBoolean())
        assertTrue(llmSettingsStore.get("s-b") == null, "у B строки не должно быть")
    }

    @Test
    fun `PUT temperature timeout and prices in session A do not affect session B`() {
        seedSession("s-ta")
        seedSession("s-tb")

        put(
            "s-ta",
            """{"temperature":0.2,"topP":0.9,"topK":40,"timeoutSeconds":99,"priceInputPer1M":5.0,"priceOutputPer1M":5.0}"""
        )
        val a = get("s-ta")
        assertEquals(0.2, a["temperature"].asDouble(), 1e-9)
        assertEquals(0.9, a["topP"].asDouble(), 1e-9)
        assertEquals(40, a["topK"].asInt())
        assertEquals(99, a["timeoutSeconds"].asLong())
        assertEquals(5.0, a["priceInputPer1M"].asDouble(), 1e-9)
        assertEquals(5.0, a["priceOutputPer1M"].asDouble(), 1e-9)

        // сессия B не тронута — те же глобальные значения (без утечек из A)
        val b = get("s-tb")
        assertEquals(0.7, b["temperature"].asDouble(), 1e-9)
        assertEquals(1.0, b["topP"].asDouble(), 1e-9)
        assertTrue(b["topK"].isNull, "topK у B должен остаться глобальным (null): $b")
        assertEquals(60, b["timeoutSeconds"].asLong())
        assertEquals(0.1, b["priceInputPer1M"].asDouble(), 1e-9)
        assertEquals(0.1, b["priceOutputPer1M"].asDouble(), 1e-9)
        assertTrue(llmSettingsStore.get("s-tb") == null, "у B строки не должно быть")
    }

    @Test
    fun `null field removes session override and effective falls back to global`() {
        seedSession("s-nullrf")
        put("s-nullrf", """{"model":"qwen3.8-27b","temperature":0.2,"maxTokens":555,"reasoningEnabled":false}""")

        // снимаем переопределения: temperature/maxTokens/reasoning снова берутся из ГЛОБАЛЬНЫХ
        val node = put("s-nullrf", """{"temperature":null,"maxTokens":null,"reasoningEnabled":null}""")
        assertEquals("qwen3.8-27b", node["model"].asText(), "модель остаётся переопределённой")
        assertEquals(0.7, node["temperature"].asDouble(), 1e-9, "temperature после null → глобальная")
        assertEquals(10000, node["maxTokens"].asInt(), "maxTokens после null → глобальный дефолт")
        assertEquals(true, node["reasoningEnabled"].asBoolean(), "reasoningEnabled после null → глобальный (true)")
    }

    @Test
    fun `PUT partial update leaves other fields unchanged and roundtrips through GET`() {
        seedSession("s-partial")
        put("s-partial", """{"model":"deepseek-v4-flash","maxTokens":512,"reasoningEnabled":false}""")
        var node = get("s-partial")
        assertEquals("deepseek-v4-flash", node["model"].asText())
        assertEquals(1024 * 1024, node["contextLimit"].asInt())
        assertEquals(512, node["maxTokens"].asInt())
        assertEquals(false, node["reasoningEnabled"].asBoolean())

        // только модель — остальные поля сохранённой строки не меняются
        node = put("s-partial", """{"model":"qwen3.8-27b"}""")
        assertEquals("qwen3.8-27b", node["model"].asText())
        assertEquals(198 * 1024, node["contextLimit"].asInt())
        assertEquals(512, node["maxTokens"].asInt(), "maxTokens не должен измениться")
        assertEquals(false, node["reasoningEnabled"].asBoolean(), "reasoningEnabled не должен измениться")
    }

    @Test
    fun `null maxTokens and null reasoningEnabled reset to defaults`() {
        seedSession("s-null")
        put("s-null", """{"maxTokens":333,"reasoningEnabled":false}""")
        assertEquals(333, get("s-null")["maxTokens"].asInt())
        assertEquals(false, get("s-null")["reasoningEnabled"].asBoolean())

        val node = put("s-null", """{"maxTokens":null,"reasoningEnabled":null}""")
        assertEquals(10000, node["maxTokens"].asInt(), "null maxTokens → дефолт 10000")
        assertEquals(true, node["reasoningEnabled"].asBoolean(), "null reasoningEnabled → дефолт true")
    }

    @Test
    fun `PUT unknown model returns 400 with existing message and does not change model`() {
        seedSession("s-unknown")
        val e = putStatus("s-unknown", """{"model":"no_such_model"}""")
        assertEquals(HttpStatus.BAD_REQUEST, e)

        val node = get("s-unknown")
        assertEquals("default-coding", node["model"].asText(), "модель не должна измениться после 400")
    }

    @Test
    fun `PUT disabled model returns 400 with existing catalog message`() {
        seedSession("s-disabled")
        // отключаем модель в каталоге (глобальная активная модель default-coding — не из каталога, отключение разрешено)
        client.put().uri("/api/models/deepseek-v4-flash/enabled")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"enabled":false}""")
            .exchange()
            .expectStatus().isOk

        try {
            val e = putStatus("s-disabled", """{"model":"deepseek-v4-flash"}""")
            assertEquals(HttpStatus.BAD_REQUEST, e)
            // сообщение-контракт сохраняется, как в глобальном PUT
            val message = client.put().uri("/api/sessions/s-disabled/llm-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"model":"deepseek-v4-flash"}""")
                .exchange()
                .expectStatus().isBadRequest
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
            assertTrue(message.contains("Модель отключена в каталоге: deepseek-v4-flash"), "сообщение: $message")
        } finally {
            // возвращаем как было — тесты класса делят один временный SQLite-файл
            client.put().uri("/api/models/deepseek-v4-flash/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"enabled":true}""")
                .exchange()
                .expectStatus().isOk
        }
    }

    @Test
    fun `PUT maxTokens beyond model context window returns 400`() {
        seedSession("s-window")
        // сначала выбираем qwen3.8-27b (окно 198K = 202752 токена)
        put("s-window", """{"model":"qwen3.8-27b"}""")

        val e = putStatus("s-window", """{"maxTokens":500000}""")
        assertEquals(HttpStatus.BAD_REQUEST, e)

        val node = get("s-window")
        assertEquals(10000, node["maxTokens"].asInt(), "maxTokens не должен измениться после 400")
        assertEquals("qwen3.8-27b", node["model"].asText(), "модель должна остаться выбранной")
    }

    @Test
    fun `PUT non positive maxTokens and bad reasoning return 400`() {
        seedSession("s-bad")
        assertEquals(HttpStatus.BAD_REQUEST, putStatus("s-bad", """{"maxTokens":0}"""))
        assertEquals(HttpStatus.BAD_REQUEST, putStatus("s-bad", """{"maxTokens":-5}"""))
        assertEquals(HttpStatus.BAD_REQUEST, putStatus("s-bad", """{"reasoningEnabled":"yes"}"""))
        assertEquals(HttpStatus.BAD_REQUEST, putStatus("s-bad", """{"contextLimit":0}"""))

        // после серии 400 настройки не изменились
        val node = get("s-bad")
        assertEquals("default-coding", node["model"].asText())
        assertEquals(10000, node["maxTokens"].asInt())
        assertEquals(true, node["reasoningEnabled"].asBoolean())
    }

    @Test
    fun `GET and PUT work for session without messages`() {
        // Сессия без сообщений (создана кнопкой «+»): GET отдаёт глобальный эффективный набор
        val node = get("ghost")
        assertEquals("default-coding", node["model"].asText())
        assertTrue(llmSettingsStore.get("ghost") == null, "GET не должен персистить строку")

        // PUT сохраняет переопределения — применятся, когда сессия начнёт диалог
        put("ghost", """{"model":"qwen3.8-27b"}""")
        assertEquals("qwen3.8-27b", get("ghost")["model"].asText())
        assertNotNull(llmSettingsStore.get("ghost"))
    }

    @Test
    fun `DELETE session also removes its llm settings row`() {
        seedSession("s-del")
        put("s-del", """{"model":"glm-5.3-flash","maxTokens":42,"reasoningEnabled":false}""")
        assertNotNull(llmSettingsStore.get("s-del"))

        client.delete().uri("/api/sessions/s-del")
            .exchange()
            .expectStatus().isOk

        assertTrue(llmSettingsStore.get("s-del") == null, "после DELETE строка настроек должна исчезнуть")
        // после удаления сессии GET снова отдаёт глобальный эффективный набор (строки нет)
        val node = get("s-del")
        assertEquals("default-coding", node["model"].asText())
    }
}
