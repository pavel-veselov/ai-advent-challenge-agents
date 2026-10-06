package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Юнит-тесты Ollama-клиента: OpenAI-совместимый `/v1/chat/completions` БЕЗ
 * Authorization-заголовка, БЕЗ `chat_template_kwargs` (Ollama их не понимает),
 * per-request настройки перекрывают дефолты конструктора, SSE-разбор, 4xx → LlmApiException.
 */
class OllamaLlmClientTest {

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

    private fun props(model: String = "qwen2.5-coder:3b", reasoningEnabled: Boolean = true) = LlmProperties(
        provider = "ollama",
        ollamaBaseUrl = server.url("/").toString(),
        model = model,
        reasoningEnabled = reasoningEnabled,
    )

    private fun client(p: LlmProperties = props()) =
        OllamaLlmClient(p, ObjectMapper(), LlmSettings.from(p))

    private fun enqueueOk() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ок\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
    }

    @Test
    fun `sends openai compatible request without authorization header`() {
        enqueueOk()
        client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertNull(recorded.getHeader("Authorization"), "локальная Ollama не требует ключа — заголовок не отправляется")

        val body = ObjectMapper().readTree(recorded.body.readUtf8())
        assertEquals("qwen2.5-coder:3b", body["model"].asText())
        assertTrue(body["stream"].asBoolean())
        assertTrue(!body.has("chat_template_kwargs"), "Ollama не понимает chat_template_kwargs — не отправляем")
        assertTrue(!body.has("tools"), "пустой tools не должен отправляться")
    }

    @Test
    fun `does not send chat_template_kwargs even when reasoning disabled`() {
        enqueueOk()
        client(props(reasoningEnabled = false)).streamChat(emptyList(), emptyList())
            .collectList().block(Duration.ofSeconds(10))

        val body = ObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertTrue(!body.has("chat_template_kwargs"), "enable_thinking — параметр GPUStack, Ollama его не получает")
    }

    @Test
    fun `streams content and finishes with usage and assembled response`() {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Привет\"},\"finish_reason\":null}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse)
        )

        val events = client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))!!

        assertEquals("Привет", events.filterIsInstance<LlmEvent.ContentDelta>().joinToString("") { it.delta })
        val finished = events.filterIsInstance<LlmEvent.Finished>().single()
        assertEquals("stop", finished.finishReason)
        assertEquals(11, finished.usage?.inputTokens)
        assertEquals(3, finished.usage?.outputTokens)
        assertTrue(events.last() is LlmEvent.ResponseAssembled, "ответ собирается в chat.completion вид")
    }

    @Test
    fun `per-request settings override constructor defaults`() {
        enqueueOk()
        val p = props()
        val client = OllamaLlmClient(p, ObjectMapper(), LlmSettings.from(p))

        val perRequest = LlmSettings.from(
            LlmProperties(provider = "ollama", model = "deepseek-r1:14b", temperature = 0.1, topK = 24, maxTokens = 123)
        )
        client.streamChat(emptyList(), emptyList(), perRequest).collectList().block(Duration.ofSeconds(10))

        val body = ObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertEquals("deepseek-r1:14b", body["model"].asText())
        assertEquals(0.1, body["temperature"].asDouble(), 1e-9)
        assertEquals(24, body["top_k"].asInt())
        assertEquals(123, body["max_tokens"].asInt())
    }

    @Test
    fun `4xx response surfaces LlmApiException with status`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("""{"error":"model 'x' not found, try pulling it first"}""")
        )

        val ex = org.junit.jupiter.api.Assertions.assertThrows(LlmApiException::class.java) {
            client().streamChat(emptyList(), emptyList()).collectList().block(Duration.ofSeconds(10))
        }
        assertEquals(400, ex.status)
        assertTrue(ex.message!!.contains("not found"), "текст ошибки апстрима попадает в исключение: ${ex.message}")
    }
}
