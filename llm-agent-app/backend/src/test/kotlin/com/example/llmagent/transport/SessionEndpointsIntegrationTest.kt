package com.example.llmagent.transport

import com.example.llmagent.agent.SessionStore
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Интеграционный тест эндпоинта DELETE /api/sessions/{sessionId}.
 *
 * Используется отдельный временный SQLite-файл (рабочую БД ./data не трогаем).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionEndpointsIntegrationTest {

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

    @Test
    fun `DELETE session returns deleted true and clears history`() {
        sessionStore.append("it-delete", "user", "привет")
        sessionStore.append("it-delete", "assistant", "привет!")

        val body = client.delete().uri("/api/sessions/it-delete")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(body.contains("\"deleted\":true"), "ожидался {\"deleted\":true}, получено: $body")

        // вся история удалена из хранилища
        assertTrue(sessionStore.get("it-delete").isEmpty(), "история сессии должна опустеть после DELETE")
        // и в хранилище не остаётся следов для других сессий
        val keep = client.post().uri("/api/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"sessionId":"it-delete-keep","message":"привет"}""".toByteArray(StandardCharsets.UTF_8))
            .exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody
            .collectList()
            .block()
        assertTrue(keep != null && keep.isNotEmpty(), "chat-stream должен ответить")

        // DELETE для несуществующей сессии — тоже 200
        client.delete().uri("/api/sessions/never-existed")
            .exchange()
            .expectStatus().isOk

        // удаляем и вторую — история любого размера сносится целиком
        client.delete().uri("/api/sessions/it-delete-keep")
            .exchange()
            .expectStatus().isOk
        assertTrue(sessionStore.get("it-delete-keep").isEmpty())
    }
}
