package com.example.llmagent.transport

import com.example.llmagent.agent.SessionStore
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест эндпоинтов GET /api/sessions и GET /api/stats.
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionStatsIntegrationTest {

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

    private val om = ObjectMapper()

    @Test
    fun `GET sessions returns per-session aggregates with tokens and cost`() {
        sessionStore.append("stats-s1", "user", "привет", 100, null)
        sessionStore.append("stats-s1", "assistant", "ответ", 50, 200)
        sessionStore.append("stats-s2", "user", "вопрос", 25, null)

        val body = client.get().uri("/api/sessions")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("\"sessionId\":\"stats-s1\""), "нет stats-s1: $body")
        assertTrue(body.contains("\"sessionId\":\"stats-s2\""), "нет stats-s2: $body")
        assertTrue(body.contains("\"promptTokens\":"), "нет promptTokens: $body")
        assertTrue(body.contains("\"completionTokens\":"), "нет completionTokens: $body")
        assertTrue(body.contains("\"costUsd\":"), "нет costUsd: $body")
        assertTrue(body.contains("\"messageCount\":2"), "нет messageCount 2: $body")
        assertTrue(body.contains("\"promptTokens\":150"), "нет promptTokens 150: $body")
        assertTrue(body.contains("\"completionTokens\":200"), "нет completionTokens 200: $body")
        assertTrue(body.contains("\"messageCount\":1"), "нет messageCount 1: $body")
        assertTrue(body.contains("\"lastActivity\":"), "нет lastActivity: $body")
        assertTrue(body.contains("\"firstUserMessage\":"), "нет firstUserMessage: $body")
        assertTrue(body.contains("\"firstUserMessage\":\"привет\""), "нет содержания первого user-сообщения: $body")
    }

    @Test
    fun `GET stats returns global aggregates across sessions`() {
        sessionStore.append("stats-agg", "user", "вопрос", 100, null)
        sessionStore.append("stats-agg", "assistant", "ответ", 40, 60)

        val body = client.get().uri("/api/stats")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("\"sessionCount\":"), "нет sessionCount: $body")
        assertTrue(body.contains("\"messageCount\":"), "нет messageCount: $body")
        assertTrue(body.contains("\"promptTokens\":"), "нет promptTokens: $body")
        assertTrue(body.contains("\"completionTokens\":"), "нет completionTokens: $body")
        assertTrue(body.contains("\"costUsd\":"), "нет costUsd: $body")
        assertTrue(body.contains("\"lifetime\":"), "нет lifetime: $body")
        // в БД гарантированно есть сообщения с токенами — top-level стоимость не может быть 0.0/null
        // (lifetime.costUsd при этом может быть 0.0 — кумулятивный счётчик, его не проверяем здесь)
        val stats = om.readTree(body)
        assertTrue(stats.path("costUsd").asDouble() > 0.0, "costUsd не должен быть нулём при наличии токенов: $body")
    }

    @Test
    fun `GET stats reports lifetime counters that survive session deletion`() {
        val sid = "lt-it-${System.currentTimeMillis()}"
        sessionStore.append(sid, "user", "вопрос", 100, null)
        sessionStore.append(sid, "assistant", "ответ", 40, 60)
        sessionStore.addLifetimeTokens(40, 60, 0.0)

        val before = om.readTree(
            client.get().uri("/api/stats")
                .exchange().expectStatus().isOk
                .expectBody(String::class.java).returnResult().responseBody!!
        )
        val lifetimeBefore = before.path("lifetime")
        assertTrue(lifetimeBefore.path("sessions").asLong() >= 1, "lifetime.sessions должен быть >= 1: $before")

        client.delete().uri("/api/sessions/$sid").exchange().expectStatus().isOk

        val after = om.readTree(
            client.get().uri("/api/stats")
                .exchange().expectStatus().isOk
                .expectBody(String::class.java).returnResult().responseBody!!
        )
        val lifetimeAfter = after.path("lifetime")
        assertEquals(lifetimeBefore.path("sessions").asLong(), lifetimeAfter.path("sessions").asLong())
        assertEquals(lifetimeBefore.path("promptTokens").asLong(), lifetimeAfter.path("promptTokens").asLong())
        assertEquals(lifetimeBefore.path("completionTokens").asLong(), lifetimeAfter.path("completionTokens").asLong())
        assertEquals(lifetimeBefore.path("totalTokens").asLong(), lifetimeAfter.path("totalTokens").asLong())
        assertEquals(lifetimeBefore.path("costUsd").asDouble(), lifetimeAfter.path("costUsd").asDouble())
        // удалённая сессия исчезает только из текущих (не кумулятивных) счётчиков
        assertTrue(
            after.path("sessionCount").asLong() < before.path("sessionCount").asLong(),
            "sessionCount должен уменьшиться после удаления сессии: $before -> $after",
        )
    }
}
