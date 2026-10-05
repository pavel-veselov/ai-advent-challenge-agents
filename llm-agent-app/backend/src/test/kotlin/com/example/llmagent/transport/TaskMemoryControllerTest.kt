package com.example.llmagent.transport

import com.example.llmagent.agent.Agent
import com.example.llmagent.agent.JdbcTaskMemoryStore
import com.example.llmagent.agent.SessionStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест GET /api/sessions/{sessionId}/task-memory (Day-25):
 * пустая структура со статусом 200 для неизвестной сессии (НИКОГДА не 404),
 * выдача сохранённого состояния, и контракт «DELETE сессии НЕ удаляет память
 * задачи» (строка task_memory остаётся — см. CONTRACT.md).
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskMemoryControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-tm-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var taskMemoryStore: JdbcTaskMemoryStore

    @Autowired
    lateinit var sessionStore: SessionStore

    /** Мокаем агента, чтобы /chat и /continue не ходили в реальный LLM. */
    @MockBean
    lateinit var agent: Agent

    private val om = ObjectMapper()

    private fun get(id: String): JsonNode {
        val body = client.get().uri("/api/sessions/$id/task-memory")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    @Test
    fun `GET unknown session returns 200 with empty state`() {
        val node = get("tmc-none")
        assertEquals("", node["goal"].asText(), "цель пустая — панель у новой сессии просто пустая")
        assertEquals(0, node["clarifications"].size())
        assertEquals(0, node["constraints"].size())
        assertTrue(node["updatedAt"].isNull, "у пустого состояния нет updated_at")
    }

    @Test
    fun `GET returns saved state`() {
        taskMemoryStore.upsert(
            "tmc-seeded",
            "Собрать отчёт",
            listOf("формат JSON"),
            listOf("язык — русский"),
        )

        val node = get("tmc-seeded")
        assertEquals("Собрать отчёт", node["goal"].asText())
        assertEquals(1, node["clarifications"].size())
        assertEquals("формат JSON", node["clarifications"][0].asText())
        assertEquals(1, node["constraints"].size())
        assertEquals("язык — русский", node["constraints"][0].asText())
        assertTrue(node["updatedAt"].asText().isNotBlank())
    }

    @Test
    fun `DELETE session does not remove task memory`() {
        sessionStore.append("tmc-del", "user", "привет")
        taskMemoryStore.upsert("tmc-del", "Цель остаётся", emptyList(), emptyList())

        client.delete().uri("/api/sessions/tmc-del")
            .exchange()
            .expectStatus().isOk

        // История удалена, но строка task_memory остаётся (день-25 контракт в CONTRACT.md:
        // память задачи не чистится каскадом — осиротевшая строка недостижима из UI).
        val node = get("tmc-del")
        assertEquals("Цель остаётся", node["goal"].asText())
    }
}
