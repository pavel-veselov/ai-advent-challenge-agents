package com.example.llmagent.transport

import com.example.llmagent.config.LlmCatalog
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.io.File

/**
 * Интеграционный тест GET /api/llm/providers и runtime-переключения провайдера через
 * PUT /api/llm-settings. Живой каталог Ollama подставляется через MockWebServer
 * (`llm.ollama-base-url`); рабочая БД не трогается (временный SQLite-файл).
 *
 * Контракт GET /api/llm/providers (всегда 200):
 * `{"current":"<id>","providers":[{"id","label","models":[{"id","contextLimit","description"}]}]}`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LlmProvidersEndpointTest {

    companion object {
        private const val TAGS_BODY =
            """{"models":[{"name":"qwen2.5-coder:3b","details":{"parameter_size":"3.1B","quantization_level":"Q4_K_M"}}]}"""
        private const val SHOW_BODY = """{"model_info":{"qwen2.context_length":32768}}"""

        private val ollamaServer = MockWebServer()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            ollamaServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    return when {
                        path.startsWith("/api/tags") -> json(TAGS_BODY)
                        path.startsWith("/api/show") -> json(SHOW_BODY)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            ollamaServer.start()
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${File.createTempFile("llm-agent-prov-", ".db").absolutePath.replace('\\', '/')}"
            }
            registry.add("llm.ollama-base-url") { ollamaServer.url("/").toString() }
        }

        @JvmStatic
        @AfterAll
        fun shutdownServer() {
            ollamaServer.shutdown()
        }

        private fun json(body: String) =
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
    }

    @Autowired
    lateinit var client: WebTestClient

    private val om = ObjectMapper()

    private fun getSettings(): JsonNode = om.readTree(
        client.get().uri("/api/llm-settings").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
    )

    private fun putSettings(patch: String): JsonNode = om.readTree(
        client.put().uri("/api/llm-settings").contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
    )

    private fun getProviders(): JsonNode = om.readTree(
        client.get().uri("/api/llm/providers").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
    )

    @Test
    fun `GET providers returns both providers with catalog and live ollama models`() {
        // гарантируем стартовое состояние провайдера (предыдущие тесты могли его переключить)
        putSettings("""{"provider":"gpustack"}""")

        val body = getProviders()

        assertEquals("gpustack", body["current"].asText())
        val providers = body["providers"]
        assertTrue(providers.isArray && providers.size() == 2, "два провайдера: $body")

        val gpu = providers[0]
        assertEquals("gpustack", gpu["id"].asText())
        assertEquals("gpustack", gpu["label"].asText())
        assertEquals(
            LlmCatalog.MODELS.map { it.id },
            gpu["models"].map { it["id"].asText() },
            "модели gpustack — каталог в фиксированном порядке",
        )
        assertEquals(LlmCatalog.MODELS[0].contextWindow, gpu["models"][0]["contextLimit"].asInt())

        val ollama = providers[1]
        assertEquals("ollama", ollama["id"].asText())
        assertEquals("свой лунапарк", ollama["label"].asText(), "отображаемое имя ollama зафиксировано контрактом")
        assertEquals(1, ollama["models"].size())
        val ollamaModel = ollama["models"][0]
        assertEquals("qwen2.5-coder:3b", ollamaModel["id"].asText())
        assertEquals(32768, ollamaModel["contextLimit"].asInt(), "контекст из /api/show (архитектурный ключ)")
        assertEquals("Ollama · 3.1B · Q4_K_M", ollamaModel["description"].asText())
    }

    @Test
    fun `PUT provider ollama switches runtime provider and resets model to first ollama model`() {
        val response = putSettings("""{"provider":"ollama"}""")

        assertEquals("ollama", response["provider"].asText())
        assertEquals("qwen2.5-coder:3b", response["model"].asText(), "модель сброшена на первую модель ollama")
        assertEquals(32768, response["contextLimit"].asInt(), "контекст выведен из живого каталога")

        val settings = getSettings()
        assertEquals("ollama", settings["provider"].asText())
        assertEquals("qwen2.5-coder:3b", settings["model"].asText())

        // модель из каталога gpustack для ollama — 400
        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"qwen3.8-27b"}""")
            .exchange()
            .expectStatus().isBadRequest

        // переключаемся обратно: модель сбрасывается на первую модель каталога gpustack
        val back = putSettings("""{"provider":"gpustack"}""")
        assertEquals("gpustack", back["provider"].asText())
        assertEquals("qwen3.8-27b", back["model"].asText())
        assertEquals(198 * 1024, back["contextLimit"].asInt())
    }

    @Test
    fun `PUT with unknown provider returns 400 and keeps current provider`() {
        val before = getSettings()["provider"].asText()

        client.put().uri("/api/llm-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"provider":"vllm"}""")
            .exchange()
            .expectStatus().isBadRequest

        assertEquals(before, getSettings()["provider"].asText(), "провайдер не должен измениться после 400")
    }

    @Test
    fun `PUT with same provider value is a no-op for model`() {
        putSettings("""{"provider":"gpustack"}""")
        putSettings("""{"model":"glm-5.3-flash"}""")

        val response = putSettings("""{"provider":"gpustack"}""")

        assertEquals("gpustack", response["provider"].asText())
        assertEquals("glm-5.3-flash", response["model"].asText(), "тот же провайдер — модель не сбрасывается")
    }
}
