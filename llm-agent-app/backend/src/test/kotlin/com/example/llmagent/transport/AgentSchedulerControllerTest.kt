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
 * Интеграционный тест REST управления периодическими задачами планировщика (Day-17/18):
 * GET (список, snake_case, всегда 200), POST (201 созданная задача в snake_case; невалидное
 * тело → 400), PATCH /{id} (частичное обновление; некорректный id → 400; нет записи → 404),
 * DELETE (ok:true; некорректный id → 400; нет записи → 404). Проверяет, что camelCase поля
 * хранилища конвертируются в snake_case (interval_seconds, last_run_at, next_run_at, ...).
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentSchedulerControllerTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-asched-it-", ".db").absolutePath.replace('\\', '/')}"
            }
        }
    }

    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val om = ObjectMapper()

    @BeforeEach
    fun cleanDb() {
        // Тесты одного класса делят один Spring-контекст и один temp SQLite-файл;
        // чистим agent_scheduler_jobs, чтобы каждый тест стартовал с пустого состояния.
        try {
            jdbc.update("DELETE FROM agent_scheduler_jobs")
        } catch (_: Exception) {
        }
    }

    private fun getList(): JsonNode {
        val body = client.get().uri("/api/agent-scheduler")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun createJob(name: String, intervalSeconds: Long, prompt: String): JsonNode {
        val body = client.post().uri("/api/agent-scheduler")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"$name","interval_seconds":$intervalSeconds,"prompt":"$prompt"}""")
            .exchange()
            .expectStatus().isCreated
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
    fun `POST creates job with snake_case fields`() {
        val dto = createJob("погода", 300, "расскажи про погоду")
        assertTrue(dto["id"].asLong() > 0, "задача получила id")
        assertEquals("погода", dto["name"].asText())
        assertEquals(300, dto["interval_seconds"].asInt(), "snake_case interval_seconds")
        assertEquals("расскажи про погоду", dto["prompt"].asText())
        assertTrue(dto["enabled"].asBoolean(), "новая задача включена")
        assertTrue(dto.has("last_run_at"), "lastRunAt → last_run_at (null у новой)")
        assertTrue(dto.has("next_run_at"), "nextRunAt → next_run_at")
        assertTrue(dto.has("created_at"), "createdAt → created_at")
        assertTrue(dto.has("updated_at"), "updatedAt → updated_at")
        // camelCase ключи не должны остаться в ответе.
        assertFalse(dto.has("intervalSeconds"), "camelCase intervalSeconds не должен остаться")
        assertFalse(dto.has("lastRunAt"), "camelCase lastRunAt не должен остаться")

        // Тот же джоб виден в GET (snake_case).
        val list = getList()
        assertEquals(1, list.size())
        assertEquals("погода", list[0]["name"].asText())
        assertEquals(300, list[0]["interval_seconds"].asInt())
    }

    @Test
    fun `POST invalid body returns 400`() {
        // blank prompt
        val body1 = client.post().uri("/api/agent-scheduler")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"x","interval_seconds":60,"prompt":"  "}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body1.contains("обязательны"), "сообщение 400: $body1")

        // non-positive interval
        val body2 = client.post().uri("/api/agent-scheduler")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"x","interval_seconds":0,"prompt":"п"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body2.contains("обязательны"), "сообщение 400: $body2")
    }

    @Test
    fun `PATCH partially updates and preserves other fields`() {
        val created = createJob("job", 300, "промпт")
        val id = created["id"].asLong()

        val patched = client.patch().uri("/api/agent-scheduler/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"interval_seconds":60,"enabled":false}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val node = om.readTree(patched)
        assertEquals(60, node["interval_seconds"].asInt(), "интервал обновлён")
        assertFalse(node["enabled"].asBoolean(), "задача выключена")
        assertEquals("job", node["name"].asText(), "имя сохраняется")
        assertEquals("промпт", node["prompt"].asText(), "промпт сохраняется")
    }

    @Test
    fun `PATCH invalid id returns 400`() {
        val body = client.patch().uri("/api/agent-scheduler/abc")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("Некорректный id"), "400: $body")
    }

    @Test
    fun `PATCH nonexistent id returns 404`() {
        val body = client.patch().uri("/api/agent-scheduler/999999")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"interval_seconds":60}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("не найдена"), "404: $body")
    }

    @Test
    fun `DELETE removes job and second delete returns 404`() {
        val created = createJob("job", 60, "промпт")
        val id = created["id"].asLong()

        val body = client.delete().uri("/api/agent-scheduler/$id")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("\"ok\":true"), "ok:true — $body")
        assertTrue(getList().isEmpty(), "после удаления список пуст")

        client.delete().uri("/api/agent-scheduler/$id")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `DELETE invalid id returns 400`() {
        val body = client.delete().uri("/api/agent-scheduler/abc")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("Некорректный id"), "400: $body")
    }
}
