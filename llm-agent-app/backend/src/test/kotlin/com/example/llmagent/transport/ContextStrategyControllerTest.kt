package com.example.llmagent.transport

import com.example.llmagent.agent.SessionBranchStore
import com.example.llmagent.agent.SessionCompressionStore
import com.example.llmagent.agent.SessionContextStore
import com.example.llmagent.agent.SessionFactsStore
import com.example.llmagent.agent.SessionStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест GET/PUT /api/sessions/{sessionId}/context-strategy и
 * GET /api/sessions/{sessionId}/facts: дефолты, частичное обновление, валидация (400),
 * синхронизация с legacy-сжатием, побочные эффекты branching, чистка при DELETE.
 *
 * Отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextStrategyControllerTest {

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
    lateinit var compressionStore: SessionCompressionStore

    @Autowired
    lateinit var contextStore: SessionContextStore

    @Autowired
    lateinit var factsStore: SessionFactsStore

    @Autowired
    lateinit var branchStore: SessionBranchStore

    private val om = ObjectMapper()

    private fun seed(id: String, messages: Int = 2) {
        repeat(messages / 2) {
            sessionStore.append(id, "user", "вопрос")
            sessionStore.append(id, "assistant", "ответ")
        }
        if (messages % 2 == 1) sessionStore.append(id, "user", "вопрос")
    }

    private fun get(id: String): JsonNode {
        val body = client.get().uri("/api/sessions/$id/context-strategy")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    private fun put(id: String, json: String): JsonNode {
        val body = client.put().uri("/api/sessions/$id/context-strategy")
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
    fun `GET returns defaults for existing session`() {
        seed("cs-default")
        val node = get("cs-default")
        assertEquals("cs-default", node["sessionId"].asText())
        assertEquals("none", node["strategy"].asText())
        assertEquals(12, node["windowSize"].asInt())
    }

    @Test
    fun `GET returns defaults for session without messages`() {
        val node = get("no-such-session")
        assertEquals("no-such-session", node["sessionId"].asText())
        assertEquals("none", node["strategy"].asText())
        assertEquals(12, node["windowSize"].asInt())
    }

    @Test
    fun `PUT partial update keeps other fields`() {
        seed("cs-partial")
        val response = put("cs-partial", """{"strategy":"sliding_window"}""")
        assertEquals("sliding_window", response["strategy"].asText())
        assertEquals(12, response["windowSize"].asInt(), "windowSize не должен измениться")

        val after = put("cs-partial", """{"windowSize":7}""")
        assertEquals("sliding_window", after["strategy"].asText(), "strategy не должна измениться")
        assertEquals(7, after["windowSize"].asInt())

        val roundtrip = get("cs-partial")
        assertEquals("sliding_window", roundtrip["strategy"].asText())
        assertEquals(7, roundtrip["windowSize"].asInt())
    }

    @Test
    fun `PUT full update roundtrips through GET`() {
        seed("cs-full")
        put("cs-full", """{"strategy":"sticky_facts","windowSize":4}""")
        val node = get("cs-full")
        assertEquals("sticky_facts", node["strategy"].asText())
        assertEquals(4, node["windowSize"].asInt())
    }

    @Test
    fun `PUT works for session without messages`() {
        val node = put("ghost", """{"strategy":"branching","windowSize":3}""")
        assertEquals("branching", node["strategy"].asText())
        assertEquals(3, node["windowSize"].asInt())
    }

    @Test
    fun `PUT validation rejects unknown strategy and bad windowSize with 400`() {
        seed("cs-bad")
        val badBodies = listOf(
            """{"strategy":"quantum"}""",
            """{"strategy":42}""",
            """{"windowSize":0}""",
            """{"windowSize":51}""",
            """{"windowSize":"abc"}""",
            """{"windowSize":null}""",
        )
        for (json in badBodies) {
            client.put().uri("/api/sessions/cs-bad/context-strategy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isBadRequest
        }
        // после серии 400 — дефолты
        val node = get("cs-bad")
        assertEquals("none", node["strategy"].asText())
        assertEquals(12, node["windowSize"].asInt())
    }

    @Test
    fun `PUT summary enables legacy compression and other strategies disable it`() {
        seed("cs-sync")
        put("cs-sync", """{"strategy":"sliding_window"}""")
        // не-summary стратегия выключает legacy-сжатие
        val disabled = getCompression("cs-sync")
        assertEquals(false, disabled["enabled"].asBoolean())

        put("cs-sync", """{"strategy":"summary"}""")
        // summary включает legacy-сжатие — сессии с /compression работают как раньше
        val enabled = getCompression("cs-sync")
        assertEquals(true, enabled["enabled"].asBoolean())

        put("cs-sync", """{"strategy":"none"}""")
        val afterNone = getCompression("cs-sync")
        assertEquals(false, afterNone["enabled"].asBoolean())
    }

    @Test
    fun `PUT summary flips compression and stays summary via resolution`() {
        seed("cs-sum")
        put("cs-sum", """{"strategy":"summary"}""")
        assertTrue(compressionStore.getSettings("cs-sum").enabled)
        // resolution: явная summary → summary даже если бы сжатие было выключено
        assertEquals("summary", contextStore.resolve("cs-sum", compressionStore.getSettings("cs-sum").enabled))
    }

    @Test
    fun `PUT branching backfills history and creates default branch`() {
        val id = "cs-branch"
        seed(id, 4) // 2 user + 2 assistant
        put(id, """{"strategy":"branching"}""")

        val branches = branchStore.list(id)
        assertEquals(1, branches.size, "создаётся ветка по умолчанию")
        assertEquals("Основная", branches[0].name)
        // голова «Основной» — последнее не-системное сообщение (бэккафилл связал историю)
        val stored = sessionStore.getStored(id)
        assertEquals(stored.last { it.role != "system" }.id, branches[0].headMessageId)
        // parent_id связаны в линейную цепочку
        val parents = stored.map { it.parentId }
        assertEquals(stored[0].id, parents[1])
        assertEquals(stored[1].id, parents[2])
        assertEquals(stored[2].id, parents[3])
        // legacy-сжатие выключено стратегией branching
        assertFalse(compressionStore.getSettings(id).enabled)
    }

    @Test
    fun `GET facts returns stored facts in insertion order`() {
        val id = "cs-facts"
        seed(id)
        factsStore.replaceAll(id, linkedMapOf("имя" to "Аня", "цель" to "тест"))
        val body = client.get().uri("/api/sessions/$id/facts")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val node = om.readTree(body)
        assertEquals(id, node["sessionId"].asText())
        assertEquals("Аня", node["facts"]["имя"].asText())
        assertEquals("тест", node["facts"]["цель"].asText())
    }

    @Test
    fun `GET facts returns empty object for session without facts`() {
        seed("cs-facts-empty")
        val body = client.get().uri("/api/sessions/cs-facts-empty/facts")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val node = om.readTree(body)
        assertTrue(node["facts"].isObject)
        assertTrue(node["facts"].isEmpty)
    }

    @Test
    fun `DELETE session also removes strategy facts and branches`() {
        val id = "cs-del"
        seed(id)
        put(id, """{"strategy":"branching"}""")
        factsStore.replaceAll(id, linkedMapOf("k" to "v"))

        client.delete().uri("/api/sessions/$id")
            .exchange()
            .expectStatus().isOk

        // стратегия вернулась к дефолту, факты и ветки удалены
        assertEquals("none", contextStore.get(id).strategy)
        assertTrue(factsStore.getAll(id).isEmpty())
        assertTrue(branchStore.list(id).isEmpty())
        val node = get(id)
        assertEquals("none", node["strategy"].asText())
    }

    private fun getCompression(id: String): JsonNode {
        val body = client.get().uri("/api/sessions/$id/compression")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }
}
