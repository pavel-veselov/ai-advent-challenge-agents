package com.example.llmagent.transport

import java.nio.charset.StandardCharsets
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatTransportIntegrationTest {

    @Autowired
    lateinit var client: WebTestClient

    private val timeout = Duration.ofSeconds(30)

    @Test
    fun `chat sse stream matches contract ordering and history is stored`() {
        val body = """{"sessionId":"it-chat-1","message":"сколько будет 2+2?"}"""
            .toByteArray(StandardCharsets.UTF_8)

        val frames = client.post().uri("/api/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult(String::class.java)
            .responseBody
            .collectList()
            .block(timeout)!!

        val all = frames.joinToString("\n")

        listOf(
            "agent_started", "llm_request_started", "tool_call_started",
            "tool_call_finished", "llm_request_started", "agent_finished",
        ).forEach { m -> assertTrue(all.contains("\"type\":\"$m\""), "no event $m in stream") }
        assertTrue(all.contains("calculator"), "no calculator in tool payload")

        val markers = listOf(
            "\"type\":\"agent_started\"",
            "\"type\":\"tool_call_started\"",
            "\"type\":\"tool_call_finished\"",
            "\"type\":\"agent_finished\"",
        )
        var last = -1
        for (marker in markers) {
            val at = all.indexOf(marker)
            assertTrue(at > last, "marker $marker out of order")
            last = at
        }

        val history = client.get().uri("/api/sessions/it-chat-1/history")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        assertTrue(history.contains("сколько будет 2+2?"), "history missing user message")
        assertTrue(history.contains("Результат: 4"), "history missing assistant result")
    }
}
