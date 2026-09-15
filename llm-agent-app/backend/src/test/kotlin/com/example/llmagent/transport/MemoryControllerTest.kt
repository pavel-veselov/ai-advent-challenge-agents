package com.example.llmagent.transport

import com.example.llmagent.agent.ProjectStore
import com.example.llmagent.agent.SessionStore
import com.example.llmagent.agent.WorkingMemoryStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест памяти агента (memory layers, day-13: память пишется ТОЛЬКО
 * пользователем). Рабочая память — ПАМЯТЬ ПРОЕКТА (POST .../memory/notes — заметка
 * пользователя; GET /api/projects/{projectId}/memory; POST .../memory/new-task — сброс
 * WM проекта; общая для всех сессий проекта), долговременная память — ГЛОБАЛЬНАЯ
 * (POST/DELETE /api/sessions/{sessionId}/memory/long-term остаются session-нейтральными
 * по формату, sourceSessionId = сессия запроса). Удаление СЕССИИ рабочую память ПРОЕКТА
 * НЕ трогает (она общая для всех сессий проекта) — проверяется явно.
 *
 * Отдельный временный SQLite-файл (рабочую БД ./data не трогаем); тесты упорядочены,
 * т.к. longTerm глобальный и «свежая память» должна читаться до записи LTM другими тестами.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MemoryControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-memory-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var workingMemoryStore: WorkingMemoryStore

    @Autowired
    lateinit var projectStore: ProjectStore

    @Autowired
    lateinit var sessionStore: SessionStore

    private val om = ObjectMapper()

    private fun getProjectMemory(projectId: Long): JsonNode {
        val body = client.get().uri("/api/projects/$projectId/memory")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun postLongTerm(sessionId: String, json: String): JsonNode {
        val body = client.post().uri("/api/sessions/$sessionId/memory/long-term")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun postNote(projectId: Long, json: String): JsonNode {
        val body = client.post().uri("/api/projects/$projectId/memory/notes")
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
    @Order(1)
    fun `GET memory of fresh project returns empty structures`() {
        val node = getProjectMemory(424242)
        assertTrue(node["working"].isObject)
        assertTrue(node["working"]["task"].isNull, "task у проекта без памяти — null")
        assertTrue(node["working"]["notes"].isArray && node["working"]["notes"].isEmpty, "notes у проекта без памяти — []")
        assertTrue(node["longTerm"].isArray && node["longTerm"].isEmpty, "longTerm глобальный, но в чистой БД — []")
    }

    @Test
    @Order(2)
    fun `POST long-term creates entry and upserts same type-key from another session`() {
        val created = postLongTerm(
            "mem-ltm-a",
            """{"type":"profile","key":"mem-ltm-upsert","value":"v1"}""",
        )
        assertTrue(created["id"].asLong() != -1L, "запись должна получить реальный id из БД")
        assertEquals("mem-ltm-a", created["sourceSessionId"].asText())
        assertEquals("profile", created["type"].asText())
        assertEquals("mem-ltm-upsert", created["key"].asText())
        assertEquals("v1", created["value"].asText())
        assertTrue(created.has("createdAt") && !created["createdAt"].asText().isBlank())
        assertTrue(created.has("updatedAt") && !created["updatedAt"].asText().isBlank())

        // тот же (type, key) из другой сессии — upsert: тот же id, новое value, новый sourceSessionId
        val upserted = postLongTerm(
            "mem-ltm-b",
            """{"type":"profile","key":"mem-ltm-upsert","value":"v2"}""",
        )
        assertEquals(created["id"].asLong(), upserted["id"].asLong(), "upsert сохраняет id записи")
        assertEquals("v2", upserted["value"].asText())
        assertEquals("mem-ltm-b", upserted["sourceSessionId"].asText())
    }

    @Test
    @Order(3)
    fun `POST long-term validation rejects bad type and blank key or value with 400`() {
        val badBodies = listOf(
            """{"type":"invalid","key":"k","value":"v"}""",
            """{"type":"","key":"k","value":"v"}""",
            """{"type":"profile","key":"  ","value":"v"}""",
            """{"type":"profile","key":"k","value":""}""",
            """{"type":"profile","key":"k","value":"   "}""",
            """{"key":"k","value":"v"}""",
            """{"type":"profile","value":"v"}""",
        )
        for (json in badBodies) {
            client.post().uri("/api/sessions/mem-bad/memory/long-term")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody(String::class.java)
        }
    }

    @Test
    @Order(4)
    fun `DELETE long-term entry returns 200 then 404 on second delete`() {
        val created = postLongTerm(
            "mem-del",
            """{"type":"knowledge","key":"mem-del-k","value":"факт"}""",
        )
        val id = created["id"].asLong()

        client.delete().uri("/api/sessions/mem-del/memory/long-term/$id")
            .exchange()
            .expectStatus().isOk

        client.delete().uri("/api/sessions/mem-del/memory/long-term/$id")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    @Order(5)
    fun `POST new-task clears working memory of project`() {
        val projectId = projectStore.create("проект для new-task")
        // WM живёт на ПРОЕКТЕ — пишем и читаем по project_id; все сессии проекта её разделяют
        workingMemoryStore.setTask(projectId.toString(), "написать тесты")
        workingMemoryStore.appendNote(projectId.toString(), "заметка")
        assertEquals("написать тесты", workingMemoryStore.get(projectId.toString()).task)

        client.post().uri("/api/projects/$projectId/memory/new-task")
            .exchange()
            .expectStatus().isOk

        val node = getProjectMemory(projectId)
        assertTrue(node["working"]["task"].isNull, "после new-task task должен стать null")
        assertTrue(node["working"]["notes"].isEmpty, "после new-task notes должны стать []")
    }

    @Test
    @Order(6)
    fun `DELETE session keeps project working memory and global long-term`() {
        val projectId = projectStore.create("проект, где удаляют сессию")
        val sessionId = sessionStore.createSession(projectId, "сессия к удалению")
        // WM проекта — ОБЩАЯ для всех сессий; её НЕ должна стирать отдельная сессия
        workingMemoryStore.setTask(projectId.toString(), "задача переживает удаление сессии")
        workingMemoryStore.appendNote(projectId.toString(), "заметка переживает удаление сессии")
        val created = postLongTerm(
            sessionId,
            """{"type":"decision","key":"mem-sess-del-k","value":"решение переживает удаление"}""",
        )

        client.delete().uri("/api/sessions/$sessionId")
            .exchange()
            .expectStatus().isOk

        // рабочая память ПРОЕКТА НЕ тронута удалением одной сессии
        val node = getProjectMemory(projectId)
        assertEquals(
            "задача переживает удаление сессии",
            node["working"]["task"].asText(),
            "удаление сессии НЕ стирает рабочую память проекта",
        )
        assertTrue(!node["working"]["notes"].isEmpty, "заметки проекта сохраняются")
        // ГЛОБАЛЬНАЯ долговременная память переживает удаление сессии — это фича
        val fromDeleted = node["longTerm"]
            .filter { it["sourceSessionId"].asText() == sessionId }
        assertEquals(1, fromDeleted.size, "LTM-запись удалённой сессии должна остаться в глобальном списке")
        assertEquals(created["id"].asLong(), fromDeleted[0]["id"].asLong())
        assertEquals("решение переживает удаление", fromDeleted[0]["value"].asText())
    }

    @Test
    @Order(7)
    fun `POST notes appends note to project working memory and GET reflects it`() {
        val projectId = projectStore.create("проект для заметок")

        val resp = postNote(projectId, """{"note":"пользовательская заметка"}""")
        assertEquals("пользовательская заметка", resp["note"].asText())
        assertTrue(resp["notes"].isArray && resp["notes"].size() == 1, "в ответе — полный список заметок")
        assertEquals("пользовательская заметка", resp["notes"][0].asText())

        // GET /memory теперь отражает пользовательскую заметку
        val node = getProjectMemory(projectId)
        assertTrue(!node["working"]["notes"].isEmpty, "заметка пользователя должна читаться через GET memory")
        assertEquals("пользовательская заметка", node["working"]["notes"][0].asText())
    }

    @Test
    @Order(8)
    fun `POST notes blank or missing note returns 400`() {
        val projectId = projectStore.create("проект для пустых заметок")
        val badBodies = listOf(
            """{"note":""}""",
            """{"note":"   "}""",
            """{}""",
            """{"other":"field"}""",
        )
        for (json in badBodies) {
            client.post().uri("/api/projects/$projectId/memory/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody(String::class.java)
        }
        // ничего не записалось
        assertTrue(getProjectMemory(projectId)["working"]["notes"].isEmpty)
    }

    @Test
    @Order(9)
    fun `POST notes preserves append order across calls`() {
        val projectId = projectStore.create("проект для порядка заметок")
        postNote(projectId, """{"note":"первая"}""")
        postNote(projectId, """{"note":"вторая"}""")

        val node = getProjectMemory(projectId)
        val notes = node["working"]["notes"]
        assertEquals(2, notes.size())
        assertEquals("первая", notes[0].asText())
        assertEquals("вторая", notes[1].asText())
    }

    @Test
    @Order(10)
    fun `POST notes on missing project returns 404`() {
        client.post().uri("/api/projects/987654/memory/notes")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"note":"в никуда"}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody(String::class.java)
    }
}
