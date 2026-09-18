package com.example.llmagent.transport

import com.example.llmagent.agent.Agent
import com.example.llmagent.agent.TaskStateStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux

/**
 * Интеграционный тест GET/PUT /api/sessions/{sessionId}/task-state (Day-13, FSM):
 * 404 для неначатой задачи, создание/обновление состояния, валидация этапов и переходов
 * FSM (400), пауза/продолжение (только флаг, этапы не трогаются), чистка при DELETE сессии.
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskStateControllerTest {

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
    lateinit var taskStateStore: TaskStateStore

    /** Мокаем агента, чтобы /continue не ходил в реальный LLM (тесты состояния, не потока). */
    @MockBean
    lateinit var agent: Agent

    private val om = ObjectMapper()

    private fun get(id: String): JsonNode {
        val body = client.get().uri("/api/sessions/$id/task-state")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun put(id: String, json: String, expectStatus: Int = 200): JsonNode? {
        val spec = client.put().uri("/api/sessions/$id/task-state")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
        val response = if (expectStatus == 200) spec.expectStatus().isOk else spec.expectStatus().isBadRequest
        val body = response
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
        return body?.let { om.readTree(it) }
    }

    @Test
    fun `GET returns 404 when task not started`() {
        client.get().uri("/api/sessions/ts-none/task-state")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `PUT with stage creates state and GET roundtrips`() {
        val node = put(
            "ts-create",
            """{"stage":"planning","currentStep":"уточнить цель","expectedAction":"спросить пользователя"}""",
        )!!
        assertEquals("ts-create", node["sessionId"].asText())
        assertEquals("planning", node["stage"].asText())
        assertEquals("уточнить цель", node["currentStep"].asText())
        assertEquals("спросить пользователя", node["expectedAction"].asText())
        assertEquals(false, node["paused"].asBoolean())

        val after = get("ts-create")
        assertEquals("planning", after["stage"].asText())
        assertEquals("уточнить цель", after["currentStep"].asText())
        assertTrue(after["updatedAt"].asText().isNotBlank())
    }

    @Test
    fun `PUT same stage updates step and action`() {
        put("ts-same", """{"stage":"planning","currentStep":"шаг 1"}""")
        val node = put("ts-same", """{"stage":"planning","currentStep":"шаг 2","expectedAction":"запланировать"}""")!!
        assertEquals("planning", node["stage"].asText())
        assertEquals("шаг 2", node["currentStep"].asText())
        assertEquals("запланировать", node["expectedAction"].asText())
    }

    @Test
    fun `PUT valid linear transition chain accepted`() {
        put("ts-chain", """{"stage":"planning"}""")
        put("ts-chain", """{"stage":"execution"}""")
        put("ts-chain", """{"stage":"validation"}""")
        val node = put("ts-chain", """{"stage":"done"}""")!!
        assertEquals("done", node["stage"].asText())
    }

    @Test
    fun `PUT planning to done rejected with 400`() {
        put("ts-chain-done", """{"stage":"planning"}""")
        // планирование → done запрещено: нельзя завершить, пропустив execution и validation.
        put("ts-chain-done", """{"stage":"done"}""", expectStatus = 400)
        assertEquals("planning", get("ts-chain-done")["stage"].asText(), "состояние осталось на planning")
    }

    @Test
    fun `PUT first stage must be planning - execution rejected`() {
        put("ts-first-exec", """{"stage":"execution"}""", expectStatus = 400)
        // состояние не создалось
        client.get().uri("/api/sessions/ts-first-exec/task-state")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `PUT first stage must be planning - ok`() {
        val node = put("ts-first-planning", """{"stage":"planning"}""")!!
        assertEquals("planning", node["stage"].asText())
        assertEquals("planning", get("ts-first-planning")["stage"].asText())
    }

    @Test
    fun `PUT invalid transition rejected with 400 and state unchanged`() {
        put("ts-bad-transition", """{"stage":"planning"}""")
        put("ts-bad-transition", """{"stage":"validation"}""", expectStatus = 400)
        // состояние не должно измениться
        assertEquals("planning", get("ts-bad-transition")["stage"].asText())
    }

    @Test
    fun `PUT unknown stage rejected with 400`() {
        put("ts-unknown", """{"stage":"foo"}""", expectStatus = 400)
        // и состояние не создалось
        client.get().uri("/api/sessions/ts-unknown/task-state")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `PUT without stage and paused rejected with 400`() {
        put("ts-empty-body", """{}""", expectStatus = 400)
        put("ts-empty-body", """{"currentStep":"x"}""", expectStatus = 400)
    }

    @Test
    fun `PUT paused only toggles pause and preserves stage and steps`() {
        put("ts-pause", """{"stage":"planning"}""")
        put("ts-pause", """{"stage":"execution","currentStep":"шаг","expectedAction":"действие"}""")
        val paused = put("ts-pause", """{"paused":true}""")!!
        assertTrue(paused["paused"].asBoolean())
        assertEquals("execution", paused["stage"].asText(), "этап не должен измениться")
        assertEquals("шаг", paused["currentStep"].asText())

        val resumed = put("ts-pause", """{"paused":false}""")!!
        assertFalse(resumed["paused"].asBoolean())
        assertEquals("execution", resumed["stage"].asText())
    }

    @Test
    fun `PUT paused without task rejected with 400`() {
        put("ts-pause-none", """{"paused":true}""", expectStatus = 400)
    }

    @Test
    fun `PUT paused non-boolean rejected with 400`() {
        put("ts-pause-type", """{"stage":"planning"}""")
        put("ts-pause-type", """{"paused":"yes"}""", expectStatus = 400)
    }

    @Test
    fun `PUT stage without paused preserves pause flag`() {
        put("ts-preserve", """{"stage":"planning"}""")
        put("ts-preserve", """{"stage":"execution"}""")
        put("ts-preserve", """{"paused":true}""")
        // смена этапа без поля paused — пауза сохраняется (её меняет только пользователь)
        val node = put("ts-preserve", """{"stage":"validation","currentStep":"проверка"}""")!!
        assertEquals("validation", node["stage"].asText())
        assertTrue(node["paused"].asBoolean(), "пауза должна сохраниться при смене этапа")
    }

    @Test
    fun `PUT currentStep non-string rejected with 400`() {
        put("ts-type", """{"stage":"planning","currentStep":123}""", expectStatus = 400)
    }

    @Test
    fun `DELETE session removes task state`() {
        put("ts-delete", """{"stage":"planning"}""")
        assertEquals("planning", get("ts-delete")["stage"].asText())

        client.delete().uri("/api/sessions/ts-delete")
            .exchange()
            .expectStatus().isOk

        // после удаления сессии состояние задачи тоже удалено (нет «призраков»)
        client.get().uri("/api/sessions/ts-delete/task-state")
            .exchange()
            .expectStatus().isNotFound
        assertFalse(taskStateStore.remove("ts-delete"), "строка task_state должна была быть удалена")
    }

    // ---- Воркфлоу Day-14: PUT с полями воркфлоу, /continue, /cancel ----

    @Test
    fun `PUT with plan and awaitConfirmation roundtrips`() {
        val node = put("ts-wf", """{"stage":"planning","plan":"План из трёх шагов","awaitConfirmation":true}""")!!
        assertEquals("planning", node["stage"].asText())
        assertEquals("План из трёх шагов", node["plan"].asText())
        assertEquals(true, node["awaitConfirmation"].asBoolean())

        val after = get("ts-wf")
        assertEquals("План из трёх шагов", after["plan"].asText())
        assertEquals(true, after["awaitConfirmation"].asBoolean())
    }

    @Test
    fun `PUT stage without workflow fields preserves them`() {
        put("ts-wf-preserve", """{"stage":"planning","plan":"план","awaitConfirmation":true}""")
        // смена этапа без полей воркфлоу — они сохраняются (не обнуляются)
        val node = put("ts-wf-preserve", """{"stage":"execution"}""")!!
        assertEquals("execution", node["stage"].asText())
        assertEquals("план", node["plan"].asText())
        assertEquals(true, node["awaitConfirmation"].asBoolean())
    }

    @Test
    fun `POST continue without task returns 400`() {
        client.post().uri("/api/sessions/ts-cont-ghost/task-state/continue")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `POST continue at done returns 400`() {
        put("ts-cont-done", """{"stage":"planning"}""")
        put("ts-cont-done", """{"stage":"execution"}""")
        put("ts-cont-done", """{"stage":"validation"}""")
        put("ts-cont-done", """{"stage":"done"}""")
        client.post().uri("/api/sessions/ts-cont-done/task-state/continue")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `POST cancel puts task on pause and clears await`() {
        put("ts-cancel", """{"stage":"planning","plan":"план","awaitConfirmation":true}""")
        val body = client.post().uri("/api/sessions/ts-cancel/task-state/cancel")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val node = om.readTree(body)
        assertEquals("planning", node["stage"].asText(), "этап сохраняется при отмене")
        assertEquals(true, node["paused"].asBoolean(), "отмена ставит паузу")
        assertEquals(false, node["awaitConfirmation"].asBoolean(), "ожидание подтверждения сброшено")
        assertEquals("план", node["plan"].asText(), "план сохраняется при отмене")
    }

    @Test
    fun `POST cancel without task returns 400`() {
        client.post().uri("/api/sessions/ts-cancel-ghost/task-state/cancel")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `POST continue when paused resumes current stage instead of advancing`() {
        put("ts-resume", """{"stage":"planning","plan":"план"}""")
        put("ts-resume", """{"stage":"execution","implementation":"реализация"}""")
        put("ts-resume", """{"stage":"validation"}""")
        put("ts-resume", """{"paused":true}""")
        val before = get("ts-resume")
        assertEquals("validation", before["stage"].asText())
        assertTrue(before["paused"].asBoolean())

        // «Снять паузу» = POST /continue при паузе: НЕ переводит на следующий этап (done),
        // а снимает паузу и продолжает с текущего (validation).
        Mockito.`when`(agent.continueRun(anyString())).thenReturn(Flux.empty())
        client.post().uri("/api/sessions/ts-resume/task-state/continue")
            .exchange()
            .expectStatus().isOk

        val after = get("ts-resume")
        assertEquals("validation", after["stage"].asText(), "«Снять паузу» не должна переходить на следующий этап")
        assertEquals(false, after["paused"].asBoolean(), "пауза должна быть снята")
        assertEquals("план", after["plan"].asText(), "результаты этапов сохраняются")
        assertEquals("реализация", after["implementation"].asText())
    }
}
