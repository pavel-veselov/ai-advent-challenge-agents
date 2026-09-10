package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
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
        client(LlmProperties(provider = "gpustack", baseUrl = server.url("/").toString(), apiKey = apiKey))

    /** Клиент с произвольными свойствами (модель, reasoningEnabled) — для проверки chat_template_kwargs. */
    private fun client(props: LlmProperties) =
        GpuStackLlmClient(props, ObjectMapper(), LlmSettings.from(props))

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

    private fun baseProps(model: String, reasoningEnabled: Boolean = true) = LlmProperties(
        provider = "gpustack",
        baseUrl = server.url("/").toString(),
        apiKey = "secret-key",
        model = model,
        reasoningEnabled = reasoningEnabled,
    )

    private fun enqueueOk() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        )
    }

    @Test
    fun `sends enable_thinking false when reasoning disabled for non-glm model`() {
        enqueueOk()
        client(baseProps(model = "qwen3.8-27b", reasoningEnabled = false))
            .streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))

        val body = ObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertEquals(false, body["chat_template_kwargs"]["enable_thinking"].asBoolean())
    }

    @Test
    fun `does not send chat_template_kwargs when reasoning enabled`() {
        enqueueOk()
        client(baseProps(model = "deepseek-v4-flash", reasoningEnabled = true))
            .streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))

        val body = ObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertTrue(!body.has("chat_template_kwargs"), "при включённом reasoning kwarg не уходит")
    }

    @Test
    fun `does not send chat_template_kwargs when reasoning disabled for glm model`() {
        enqueueOk()
        client(baseProps(model = "glm-5.3-flash", reasoningEnabled = false))
            .streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))

        val body = ObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertTrue(!body.has("chat_template_kwargs"), "для glm* kwarg не уходит — thinking форсирован")
    }

    @Test
    fun `glm check is case insensitive`() {
        enqueueOk()
        client(baseProps(model = "GLM-5.3-Flash", reasoningEnabled = false))
            .streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))

        val body = ObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertTrue(!body.has("chat_template_kwargs"), "префикс glm проверяется без учёта регистра")
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

    @Test
    fun `4xx response surfaces LlmApiException with parsed error message`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""")
        )

        val ex = org.junit.jupiter.api.Assertions.assertThrows(LlmApiException::class.java) {
            client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))
        }
        assertEquals(401, ex.status)
        assertEquals("Incorrect API key provided", ex.message)
    }

    @Test
    fun `4xx with non json body falls back to raw body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "text/plain")
                .setBody("not found model")
        )

        val ex = org.junit.jupiter.api.Assertions.assertThrows(LlmApiException::class.java) {
            client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))
        }
        assertEquals(404, ex.status)
        assertTrue(ex.message!!.contains("not found model"))
    }

    @Test
    fun `5xx response surfaces LlmApiException with parsed error message`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"upstream is temporarily unavailable"},"status":502}""")
        )

        val ex = org.junit.jupiter.api.Assertions.assertThrows(LlmApiException::class.java) {
            client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))
        }
        assertEquals(502, ex.status)
        assertTrue(ex.message!!.contains("upstream is temporarily unavailable"))
    }

    @Test
    fun `5xx with plain body falls back to raw body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "text/plain")
                .setBody("Service Unavailable: model queue overloaded")
        )

        val ex = org.junit.jupiter.api.Assertions.assertThrows(LlmApiException::class.java) {
            client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))
        }
        assertEquals(503, ex.status)
        assertTrue(ex.message!!.contains("model queue overloaded"))
    }
}
