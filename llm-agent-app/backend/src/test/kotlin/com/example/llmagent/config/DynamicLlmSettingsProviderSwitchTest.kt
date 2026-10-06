package com.example.llmagent.config

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты runtime-переключения провайдера (PUT /api/llm-settings, ключ `provider`):
 * сброс модели на первую модель нового провайдера, персистентность модели (НЕ провайдера),
 * валидация модели по каталогу ТЕКУЩЕГО провайдера, недоступная Ollama, no-op при том же
 * значении, провайдер НЕ восстанавливается после «рестарта».
 */
class DynamicLlmSettingsProviderSwitchTest {

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

    /** Живой каталог Ollama из двух моделей: 1 tags + 2 show (один полный проход discovery). */
    private fun enqueueOllamaCatalog() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """{"models":[
                        {"name":"qwen2.5-coder:3b","details":{"parameter_size":"3.1B","quantization_level":"Q4_K_M"}},
                        {"name":"deepseek-r1:14b","details":{"parameter_size":"14.8B","quantization_level":"Q4_K_M"}}
                    ]}"""
                )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"model_info":{"qwen2.context_length":32768}}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"model_info":{"deepseek.context_length":16384}}""")
        )
    }

    private fun ollamaDiscovery() =
        OllamaDiscovery(LlmProperties(ollamaBaseUrl = server.url("/").toString()), ObjectMapper())

    private fun deadDiscovery(): OllamaDiscovery {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/").toString()
        dead.shutdown()
        return OllamaDiscovery(LlmProperties(ollamaBaseUrl = url), ObjectMapper())
    }

    private class InMemoryStore : AppSettingsStore {
        private val map = mutableMapOf<String, String>()
        override fun save(key: String, value: String) {
            map[key] = value
        }
        override fun get(key: String): String? = map[key]
        override fun all(): Map<String, String> = map.toMap()
    }

    @Test
    fun `switching to ollama resets model to first ollama model and persists it`() {
        enqueueOllamaCatalog()
        val store = InMemoryStore()
        val s = DynamicLlmSettings(LlmProperties(model = "glm-5.3-flash"), store, ollama = ollamaDiscovery())

        s.update(mapOf("provider" to "ollama"))

        assertEquals("ollama", s.provider())
        assertEquals("qwen2.5-coder:3b", s.model(), "модель сбрасывается на первую модель живого каталога Ollama")
        assertEquals("qwen2.5-coder:3b", store.get("model"), "новая модель персистится")
        assertNull(store.get("provider"), "провайдер НЕ персистится — после рестарта берётся из env")
        assertEquals(32768, s.contextLimit(), "contextLimit выводится из живого каталога Ollama")
    }

    @Test
    fun `switching back to gpustack resets model to first catalog model`() {
        enqueueOllamaCatalog()
        val store = InMemoryStore()
        val s = DynamicLlmSettings(LlmProperties(), store, ollama = ollamaDiscovery())
        s.update(mapOf("provider" to "ollama"))
        assertEquals("qwen2.5-coder:3b", s.model())

        s.update(mapOf("provider" to "gpustack"))

        assertEquals("gpustack", s.provider())
        assertEquals("qwen3.8-27b", s.model(), "модель сбрасывается на первую модель каталога GPUStack")
        assertEquals("qwen3.8-27b", store.get("model"))
        assertEquals(198 * 1024, s.contextLimit(), "contextLimit снова из каталога GPUStack")
    }

    @Test
    fun `unknown provider is rejected with available list`() {
        val s = DynamicLlmSettings(LlmProperties(), InMemoryStore(), ollama = ollamaDiscovery())

        val e = assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("provider" to "vllm"))
        }
        assertTrue(e.message!!.contains("Неизвестный провайдер: 'vllm'"), e.message)
        assertTrue(e.message!!.contains("Доступные: gpustack, ollama"), e.message)
        assertEquals("gpustack", s.provider())
    }

    @Test
    fun `switching to unavailable ollama is rejected and nothing changes`() {
        val store = InMemoryStore()
        val s = DynamicLlmSettings(LlmProperties(model = "qwen3.8-27b"), store, ollama = deadDiscovery())

        val e = assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("provider" to "ollama"))
        }
        assertEquals("Ollama недоступна", e.message)
        assertEquals("gpustack", s.provider(), "провайдер не должен измениться")
        assertEquals("qwen3.8-27b", s.model(), "модель не должна измениться")
    }

    @Test
    fun `ollama model validation uses live catalog after switch`() {
        enqueueOllamaCatalog()
        val s = DynamicLlmSettings(LlmProperties(), InMemoryStore(), ollama = ollamaDiscovery())
        s.update(mapOf("provider" to "ollama"))

        // вторая модель живого каталога принимается (ids уже в кэше discovery)
        s.update(mapOf("model" to "deepseek-r1:14b"))
        assertEquals("deepseek-r1:14b", s.model())
        assertEquals(16384, s.contextLimit())

        // неизвестная для ollama модель отклоняется с перечнем доступных
        val e = assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("model" to "qwen3.8-27b"))
        }
        assertTrue(e.message!!.contains("Неизвестная модель: 'qwen3.8-27b'"), e.message)
        assertTrue(e.message!!.contains("deepseek-r1:14b, qwen2.5-coder:3b"), e.message)
        assertEquals("deepseek-r1:14b", s.model(), "модель не должна измениться после отказа")
    }

    @Test
    fun `model validation with unavailable ollama reports ollama down`() {
        val s = DynamicLlmSettings(
            LlmProperties(provider = "ollama", model = "qwen2.5-coder:3b"),
            InMemoryStore(),
            ollama = deadDiscovery(),
        )
        assertEquals("ollama", s.provider(), "стартовый провайдер из env")

        val e = assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("model" to "deepseek-r1:14b"))
        }
        assertEquals("Ollama недоступна", e.message)
    }

    @Test
    fun `same provider value is a no-op and does not reset model`() {
        val s = DynamicLlmSettings(LlmProperties(), InMemoryStore(), ollama = ollamaDiscovery())
        s.update(mapOf("model" to "glm-5.3-flash"))

        s.update(mapOf("provider" to "gpustack"))

        assertEquals("gpustack", s.provider())
        assertEquals("glm-5.3-flash", s.model(), "тот же провайдер — модель не сбрасывается")
    }

    @Test
    fun `persisted ollama model is not applied on restart with gpustack startup provider`() {
        enqueueOllamaCatalog()
        val store = InMemoryStore()
        val first = DynamicLlmSettings(LlmProperties(), store, ollama = ollamaDiscovery())
        first.update(mapOf("provider" to "ollama"))
        assertEquals("qwen2.5-coder:3b", first.model())

        // «перезапуск»: провайдер возвращается к env (gpustack); сохранённая ollama-модель
        // невалидна для каталога gpustack → остаёмся на дефолте из env
        val second = DynamicLlmSettings(LlmProperties(), store, ollama = ollamaDiscovery())
        assertEquals("gpustack", second.provider())
        assertEquals("default-coding", second.model(), "ollama-модель не применима для gpustack — дефолт из env")
    }
}
