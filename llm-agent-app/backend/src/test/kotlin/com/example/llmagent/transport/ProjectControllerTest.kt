package com.example.llmagent.transport

import com.example.llmagent.agent.LongTermMemoryStore
import com.example.llmagent.agent.ProjectStore
import com.example.llmagent.agent.SessionFactsStore
import com.example.llmagent.agent.SessionStore
import com.example.llmagent.agent.WorkingMemoryStore
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Интеграционный тест проектов: CRUD /api/projects, каскадное удаление проекта,
 * создание/листинг сессий проекта (POST/GET /api/projects/{id}/sessions), 400/404.
 *
 * Отдельный временный SQLite-файл (рабочую БД ./data не трогаем). Тесты независимы —
 * каждый создаёт свои проекты.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-project-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var projectStore: ProjectStore

    @Autowired
    lateinit var sessionStore: SessionStore

    @Autowired
    lateinit var workingMemoryStore: WorkingMemoryStore

    @Autowired
    lateinit var longTermMemoryStore: LongTermMemoryStore

    @Autowired
    lateinit var factsStore: SessionFactsStore

    private val om = ObjectMapper()

    private fun postProject(name: String) = client.post().uri("/api/projects")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""{"name":"$name"}""")
        .exchange()
        .expectStatus().isOk
        .expectBody(String::class.java)
        .returnResult()
        .responseBody!!

    // ------------------------------------------------------------------
    // CRUD проектов
    // ------------------------------------------------------------------

    @Test
    fun `POST creates project and GET lists it`() {
        val created = postProject("тестовый проект")
        val node = om.readTree(created)
        assertTrue(node["id"].asLong() > 0, "проект получает id")
        assertEquals("тестовый проект", node["name"].asText())
        assertTrue(node["createdAt"].asText().isNotBlank())
        assertTrue(node["updatedAt"].asText().isNotBlank())

        val list = client.get().uri("/api/projects")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(list.contains("\"name\":\"тестовый проект\""), "проект в списке: $list")
        assertTrue(list.contains("\"id\":${node["id"].asLong()}"), "id в списке: $list")
    }

    @Test
    fun `POST rejects blank project name with 400`() {
        for (bad in listOf("null", "\"\"", "\"   \"")) {
            client.post().uri("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"name":$bad}""")
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Test
    fun `PATCH renames project, 404 for missing, 400 for blank`() {
        val pid = projectStore.create("до переименования")
        val renamed = client.patch().uri("/api/projects/$pid")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"после переименования"}""")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val node = om.readTree(renamed)
        assertEquals(pid, node["id"].asLong())
        assertEquals("после переименования", node["name"].asText())

        // несуществующий проект — 404
        client.patch().uri("/api/projects/99999")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"name":"x"}""")
            .exchange().expectStatus().isNotFound

        // пустое имя — 400
        client.patch().uri("/api/projects/$pid")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"name":"  "}""")
            .exchange().expectStatus().isBadRequest
    }

    @Test
    fun `DELETE missing project returns 404`() {
        client.delete().uri("/api/projects/99999")
            .exchange().expectStatus().isNotFound
    }

    // ------------------------------------------------------------------
    // Сессии проекта
    // ------------------------------------------------------------------

    @Test
    fun `POST sessions creates session in project with server id`() {
        val pid = projectStore.create("сессии-прокт")
        val body = client.post().uri("/api/projects/$pid/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"title":"первый чат"}""")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val node = om.readTree(body)
        val sessionId = node["sessionId"].asText()
        assertTrue(sessionId.isNotBlank() && sessionId != "unknown", "server-side id: $sessionId")
        assertEquals(pid, node["projectId"].asLong())
        assertEquals("первый чат", node["title"].asText())
        // в БД сессия действительно привязана к проекту
        assertEquals(pid, sessionStore.getProjectId(sessionId))
    }

    @Test
    fun `POST sessions for missing project returns 404`() {
        client.post().uri("/api/projects/99999/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"title":"x"}""")
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `GET sessions lists project sessions with aggregates and projectId`() {
        val pid = projectStore.create("листинг-проект")
        val sessionA = sessionStore.createSession(pid, "таб-а")
        val sessionB = sessionStore.createSession(pid, "таб-б")
        sessionStore.append(sessionA, "user", "первый вопрос", 10, null)

        val body = client.get().uri("/api/projects/$pid/sessions")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(body.contains("\"sessionId\":\"$sessionA\""), "сессия A в списке: $body")
        assertTrue(body.contains("\"sessionId\":\"$sessionB\""), "сессия B в списке: $body")
        assertTrue(body.contains("\"projectId\":$pid"), "projectId в сводке: $body")
        assertTrue(body.contains("\"title\":\"таб-а\""), "title в сводке: $body")
        assertTrue(body.contains("\"messageCount\":1"), "сообщения агрегируются: $body")
    }

    @Test
    fun `GET sessions for missing project returns 404`() {
        client.get().uri("/api/projects/99999/sessions")
            .exchange().expectStatus().isNotFound
    }

    // ------------------------------------------------------------------
    // Каскадное удаление
    // ------------------------------------------------------------------

    @Test
    fun `DELETE project cascades sessions wm and per-session stores but keeps long-term`() {
        val pid = projectStore.create("каскад")
        val s1 = sessionStore.createSession(pid, "с1")
        val s2 = sessionStore.createSession(pid, "с2")
        sessionStore.append(s1, "user", "привет", 100, null)
        sessionStore.append(s1, "assistant", "ответ", 40, 60)
        workingMemoryStore.setTask(pid.toString(), "задача проекта")
        workingMemoryStore.appendNote(pid.toString(), "заметка проекта")
        factsStore.replaceAll(s1, mapOf("любимый" to "чай"))
        // LTM ГЛОБАЛЬНАЯ — запись переживает удаление проекта
        val ltm = longTermMemoryStore.upsert(s1, "profile", "автор", "Аня")

        client.delete().uri("/api/projects/$pid")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult().responseBody!!
            .also { assertTrue(it.contains("\"deleted\":true"), "ожидался deleted:true: $it") }

        // сессии и их история удалены
        assertTrue(sessionStore.listByProject(pid).isEmpty(), "сессий проекта не осталось")
        assertNull(sessionStore.getProjectId(s1), "строка chat_sessions удалена")
        assertNull(sessionStore.getProjectId(s2), "строка chat_sessions удалена")
        assertTrue(sessionStore.get(s1).isEmpty(), "сообщения удалены")
        // рабочая память ПРОЕКТА удалена
        assertNull(workingMemoryStore.get(pid.toString()).task, "WM проекта удалена")
        assertTrue(workingMemoryStore.get(pid.toString()).notes.isEmpty())
        // per-session данные удалены
        assertTrue(factsStore.getAll(s1).isEmpty(), "факты сессии удалены")
        // сам проект удалён
        assertNull(projectStore.get(pid))
        // ГЛОБАЛЬНАЯ долговременная память НЕ тронута
        assertEquals(ltm.id, longTermMemoryStore.listAll().single().id, "LTM переживает удаление проекта")
    }
}
