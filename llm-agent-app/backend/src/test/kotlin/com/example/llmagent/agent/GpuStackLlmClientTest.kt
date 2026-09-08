package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class GpuStackLlmClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun client(apiKey: String = "secret-key") =
        GpuStackLlmClient(LlmProperties(provider = "gpustack", baseUrl = server.url("/").toString(), apiKey = apiKey), ObjectMapper())

    @Test
    fun `streams content tokens then tool calls then finishes`() {
        val sse = buildString {
            append("event: message\n")
            append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Привет\"},\"finish_reason\":null}]}\n\n")
            append("event: message\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\" мир\"},\"finish_reason\":null}]}\n\n")
            append("event: message\n")
            append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"get_current_datetime\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\n")
            append("event: message\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
        )

        val events = client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))!!

        val text = events.filterIsInstance<LlmEvent.ContentDelta>().joinToString("") { it.delta }
        assertEquals("Привет мир", text)

        val calls = events.filterIsInstance<LlmEvent.ToolCallsComplete>().flatMap { it.toolCalls }
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("get_current_datetime", calls[0].name)

        val finished = events.filterIsInstance<LlmEvent.Finished>().single()
        assertEquals("tool_calls", finished.finishReason)
    }

    @Test
    fun `sends openai compatible request with bearer auth and tools`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                    data: {"choices":[{"delta":{"content":"ok"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                """.trimIndent())
        )

        client("my-api-key").streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer my-api-key", recorded.getHeader("Authorization"))

        val body = ObjectMapper().readTree(recorded.body.readUtf8())
        assertEquals("default-coding", body["model"].asText())
        assertTrue(body["stream"].asBoolean())
        assertEquals("auto", body["tool_choice"].asText())
        assertTrue(body["messages"].isArray)
        assertTrue(body["tools"].isArray)
    }

    @Test
    fun `accumulates tool call arguments across chunks`() {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expr\"}}]},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ession\\\":\\\"2+2\\\"}\"}}]},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse))

        val events = client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))!!

        val call = events.filterIsInstance<LlmEvent.ToolCallsComplete>().flatMap { it.toolCalls }.single()
        assertEquals("calculator", call.name)
        assertEquals("call_a", call.id)
        assertEquals("{\"expression\":\"2+2\"}", call.arguments)
    }

    @Test
    fun `finishes with stop when no tool calls`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        )

        val events = client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))!!

        val finished = events.filterIsInstance<LlmEvent.Finished>().single()
        assertEquals("stop", finished.finishReason)
        assertTrue(events.none { it is LlmEvent.ToolCallsComplete })
    }
}
