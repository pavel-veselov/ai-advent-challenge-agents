package com.example.llmagent.transport

import com.example.llmagent.agent.SessionBranchStore
import com.example.llmagent.agent.SessionContextStore
import com.example.llmagent.agent.SessionStore
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
 * Интеграционный тест GET/POST/PUT /api/sessions/{sessionId}/branches: создание ветки (fork),
 * валидация messageId (400), переключение активной ветки (400 на неизвестную) и связка
 * с history (chain активной ветки при стратегии branching).
 *
 * Отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BranchControllerTest {

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
    lateinit var branchStore: SessionBranchStore

    @Autowired
    lateinit var contextStore: SessionContextStore

    private val om = ObjectMapper()

    /** 4 сообщения: u1, a1, u2, a2 (дерево веток поддерживается SessionStore.append). */
    private fun seed(id: String) {
        sessionStore.append(id, "user", "u1")
        sessionStore.append(id, "assistant", "a1")
        sessionStore.append(id, "user", "u2")
        sessionStore.append(id, "assistant", "a2")
    }

    private fun getBranches(id: String): JsonNode {
        val body = client.get().uri("/api/sessions/$id/branches")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return om.readTree(body)
    }

    @Test
    fun `GET branches returns empty list for session without branches`() {
        val node = getBranches("b-empty")
        assertEquals("b-empty", node["sessionId"].asText())
        assertTrue(node["activeBranchId"].isNull, "активной ветки нет")
        assertTrue(node["branches"].isEmpty, "веток нет")
    }

    @Test
    fun `POST create from unknown message returns 400`() {
        seed("b-bad-msg")
        client.post().uri("/api/sessions/b-bad-msg/branches")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"messageId":999999}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `POST create branch forks from message becomes active and named sequentially`() {
        val id = "b-create"
        seed(id)
        val lastId = sessionStore.getStored(id).last().id

        val body = client.post().uri("/api/sessions/$id/branches")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"messageId":$lastId}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val branch = om.readTree(body)
        // объект ветки по контракту: id, name, headMessageId, createdAt
        assertTrue(branch["id"].isNumber)
        assertEquals("Ветка 2", branch["name"].asText(), "N = число веток(1 Основная) + 1")
        assertEquals(lastId, branch["headMessageId"].asLong())
        assertTrue(branch["createdAt"].isTextual)

        // созданная ветка — активная
        val state = getBranches(id)
        assertEquals(branch["id"].asLong(), state["activeBranchId"].asLong())
        assertEquals(2, state["branches"].size())
    }

    @Test
    fun `PUT switch unknown branch returns 400`() {
        seed("b-bad-switch")
        client.put().uri("/api/sessions/b-bad-switch/branches")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"activeBranchId":999999}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `PUT switches active branch and GET reflects it`() {
        val id = "b-switch"
        seed(id)
        val lastId = sessionStore.getStored(id).last().id
        val created = om.readTree(
            client.post().uri("/api/sessions/$id/branches")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"messageId":$lastId}""")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        )
        val branchA = created["id"].asLong()

        // возвращаемся на «Основную» через PUT
        val mainId = branchStore.list(id).first { it.name == "Основная" }.id
        val body = client.put().uri("/api/sessions/$id/branches")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"activeBranchId":$mainId}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val state = om.readTree(body)
        assertEquals(mainId, state["activeBranchId"].asLong())
        assertTrue(state["branches"].size() == 2)

        // переключаемся обратно на ветку A
        client.put().uri("/api/sessions/$id/branches")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"activeBranchId":$branchA}""")
            .exchange()
            .expectStatus().isOk
        assertEquals(branchA, getBranches(id)["activeBranchId"].asLong())
    }

    @Test
    fun `history returns active branch chain when branching and excluded branch messages`() {
        val id = "b-hero"
        seed(id)
        val lastSeedId = sessionStore.getStored(id).last().id

        // включаем branching (создаст «Основную» с бэккафиллом)
        client.put().uri("/api/sessions/$id/context-strategy")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"strategy":"branching"}""")
            .exchange()
            .expectStatus().isOk

        // форкаем ветку B от последнего сообщения истории
        val branch = om.readTree(
            client.post().uri("/api/sessions/$id/branches")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"messageId":$lastSeedId}""")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        )
        val branchB = branch["id"].asLong()

        // пишем в ветку B (она активна): x1, x2
        sessionStore.append(id, "user", "x1-b")
        sessionStore.append(id, "assistant", "x2-b")

        // переключаемся на «Основную» и пишем туда y1
        val mainId = branchStore.list(id).first { it.name == "Основная" }.id
        client.put().uri("/api/sessions/$id/branches")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"activeBranchId":$mainId}""")
            .exchange()
            .expectStatus().isOk
        sessionStore.append(id, "user", "y1-main")

        // history: цепочка АКТИВНОЙ ветки («Основная») — общие предки + y1; x1/x2 не входят
        val body = client.get().uri("/api/sessions/$id/history")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val history = om.readTree(body)
        assertEquals(id, history["sessionId"].asText())
        val contents = history["messages"].map { it["content"].asText() }
        assertEquals(listOf("u1", "a1", "u2", "a2", "y1-main"), contents)
        assertTrue(contents.none { it.startsWith("x") }, "сообщения другой ветки не входят: $contents")
        // у каждого сообщения появился id (additive)
        history["messages"].forEach { assertTrue(it["id"].isNumber, "ожидался id: $it") }

        // та же ветка B — её цепочка включает x1/x2
        client.put().uri("/api/sessions/$id/branches")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"activeBranchId":$branchB}""")
            .exchange()
            .expectStatus().isOk
        val bodyB = client.get().uri("/api/sessions/$id/history")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val historyB = om.readTree(bodyB)
        val contentsB = historyB["messages"].map { it["content"].asText() }
        assertEquals(listOf("u1", "a1", "u2", "a2", "x1-b", "x2-b"), contentsB)
    }

    @Test
    fun `history stays legacy and carries ids when not branching`() {
        val id = "b-legacy"
        seed(id)
        sessionStore.append(id, "system", "Сжатие контекста: системная заметка")
        val body = client.get().uri("/api/sessions/$id/history")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val history = om.readTree(body)
        // legacy: все сообщения по id (включая системную заметку)
        assertEquals(5, history["messages"].size())
        val roles = history["messages"].map { it["role"].asText() }
        assertEquals(listOf("user", "assistant", "user", "assistant", "system"), roles)
        history["messages"].forEach { assertTrue(it["id"].isNumber) }
        // сообщение имеет поля токенов по контракту
        assertTrue(history["messages"][0].has("promptTokens"))
        assertTrue(history["messages"][0].has("completionTokens"))
    }
}
