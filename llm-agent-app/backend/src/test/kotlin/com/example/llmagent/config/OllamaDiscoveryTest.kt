package com.example.llmagent.config

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты живого обнаружения моделей Ollama: `GET /api/tags` + `POST /api/show`
 * (архитектурный ключ `qwen2.context_length` и точный `<name>.context_length`),
 * дефолт 8192, недоступная Ollama → пустой каталог (никогда не исключение), кэш 30 c.
 */
class OllamaDiscoveryTest {

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

    private fun discovery(baseUrl: String = server.url("/").toString()) =
        OllamaDiscovery(LlmProperties(ollamaBaseUrl = baseUrl), ObjectMapper())

    private fun enqueueTags(vararg names: String) {
        val models = names.joinToString(",") { name ->
            """{"name":"$name","details":{"parameter_size":"3.1B","quantization_level":"Q4_K_M"}}"""
        }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"models":[$models]}""")
        )
    }

    private fun enqueueShow(body: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    @Test
    fun `parses tags and show with architecture and exact context keys`() {
        enqueueTags("qwen2.5-coder:3b", "llama3.1:8b")
        // 1-й show: архитектурный ключ qwen2.context_length (НЕ <name>.context_length)
        enqueueShow("""{"model_info":{"qwen2.context_length":32768,"general.architecture":"qwen2"}}""")
        // 2-й show: точный ключ <name>.context_length
        enqueueShow("""{"model_info":{"llama3.1:8b.context_length":131072}}""")

        val models = discovery().models()

        assertEquals(listOf("qwen2.5-coder:3b", "llama3.1:8b"), models.map { it.id })
        assertEquals(32768, models[0].contextLimit, "архитектурный ключ *.context_length распознаётся")
        assertEquals(131072, models[1].contextLimit, "точный ключ <name>.context_length тоже работает")
        assertEquals("3.1B", models[0].parameterSize)
        assertEquals("Q4_K_M", models[0].quantizationLevel)
        assertEquals("Ollama · 3.1B · Q4_K_M", models[0].description, "формат описания зафиксирован контрактом")
        assertEquals("/api/tags", server.takeRequest().path)
        assertEquals("/api/show", server.takeRequest().path)
        assertEquals("/api/show", server.takeRequest().path)
    }

    @Test
    fun `show without context_length key falls back to default 8192`() {
        enqueueTags("qwen2.5-coder:3b")
        enqueueShow("""{"model_info":{"general.parameter_count":3000000000}}""")

        val models = discovery().models()

        assertEquals(8192, models.single().contextLimit, "нет ключа context_length → дефолт 8192")
    }

    @Test
    fun `unreachable ollama yields empty catalog and available false`() {
        // «мёртвый» порт: сервер запущен и сразу погашен
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/").toString()
        dead.shutdown()

        val d = discovery(url)

        assertTrue(d.models().isEmpty(), "ошибка сети → пустой список, НИКОГДА исключение")
        assertFalse(d.available())
        assertNull(d.contextLimitOf("qwen2.5-coder:3b"))
    }

    @Test
    fun `models are cached - one fetch per ttl across repeated calls`() {
        enqueueTags("qwen2.5-coder:3b")
        enqueueShow("""{"model_info":{"qwen2.context_length":32768}}""")

        val d = discovery()
        d.models()
        d.modelIds()
        d.available()
        d.contextLimitOf("qwen2.5-coder:3b")

        assertEquals(2, server.requestCount, "1 tags + 1 show — повторные вызовы идут из кэша (30 c)")
    }
}
