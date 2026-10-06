package com.example.llmagent.config

import com.example.llmagent.agent.SqliteTestSupport
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Юнит-тесты per-session настроек при переключаемом провайдере:
 * - эффективная модель сессии применяется ТОЛЬКО если она в каталоге ТЕКУЩЕГО провайдера
 *   (ollama — живое обнаружение), иначе — текущая глобальная модель (fallback);
 * - `contextLimit` выводится из эффективной модели (живой каталог Ollama / [LlmCatalog]);
 * - PUT сессии валидирует модель по каталогу текущего провайдера (сообщения те же,
 *   что в глобальном PUT), maxTokens проверяется на контекстное окно ollama-модели.
 */
class SessionScopedLlmSettingsFallbackTest {

    @TempDir
    lateinit var tmpDir: Path

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

    /** Глобальные настройки с «некаталоговым» контекстом 999999 — чтобы отличать fallback от вывода по каталогу. */
    private fun global(provider: String, model: String): LlmSettings =
        LlmSettings.from(LlmProperties(provider = provider, model = model, contextLimit = 999999))

    @Test
    fun `session override from ollama catalog applies with live contextLimit when provider is ollama`() {
        enqueueOllamaCatalog()
        val store = JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("a.db")))
        store.save(StoredSessionLlmSettings("A", model = "qwen2.5-coder:3b"))

        val ollama = ollamaDiscovery()
        val p = SessionLlmSettingsProvider(
            store,
            global(provider = "ollama", model = "qwen2.5-coder:3b"),
            ollama = ollama,
        )

        val s = p.resolve("A")
        assertEquals("ollama", s.provider(), "provider наследуется от глобального источника")
        assertEquals("qwen2.5-coder:3b", s.model(), "переопределение из живого каталога Ollama применяется")
        assertEquals(32768, s.contextLimit(), "contextLimit выведен из живого каталога Ollama, не из глобального")
    }

    @Test
    fun `gpustack override is ignored while provider is ollama - global model wins`() {
        enqueueOllamaCatalog()
        val store = JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("b.db")))
        store.save(StoredSessionLlmSettings("A", model = "qwen3.8-27b"))

        val p = SessionLlmSettingsProvider(
            store,
            global(provider = "ollama", model = "qwen2.5-coder:3b"),
            ollama = ollamaDiscovery(),
        )

        val s = p.resolve("A")
        assertEquals("qwen2.5-coder:3b", s.model(), "qwen3.8-27b не из каталога ollama — применяется глобальная модель")
        assertEquals(
            32768,
            s.contextLimit(),
            "contextLimit выведен из эффективной глобальной ollama-модели (живой каталог), не из глобального поля",
        )
    }

    @Test
    fun `provider gpustack - catalog override applies and ollama model is ignored`() {
        val store = JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("c.db")))
        store.save(StoredSessionLlmSettings("A", model = "qwen2.5-coder:3b"))
        store.save(StoredSessionLlmSettings("B", model = "qwen3.8-27b"))

        val p = SessionLlmSettingsProvider(store, global(provider = "gpustack", model = "glm-5.3-flash"))

        val a = p.resolve("A")
        assertEquals("glm-5.3-flash", a.model(), "ollama-модель не из каталога gpustack — глобальная модель")
        assertEquals(256 * 1024, a.contextLimit(), "contextLimit по каталогу для эффективной модели glm-5.3-flash")

        val b = p.resolve("B")
        assertEquals("qwen3.8-27b", b.model(), "переопределение из каталога gpustack применяется")
        assertEquals(198 * 1024, b.contextLimit(), "contextLimit по каталогу для qwen3.8-27b")
    }

    @Test
    fun `session PUT validates model against ollama catalog and maxTokens window`() {
        enqueueOllamaCatalog()
        val store = JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("d.db")))
        val p = SessionLlmSettingsProvider(
            store,
            global(provider = "ollama", model = "qwen2.5-coder:3b"),
            ollama = ollamaDiscovery(),
        )

        // gpustack-модель для ollama — отклоняется с тем же сообщением, что в глобальном PUT
        val e = assertThrows(LlmSettingsValidationException::class.java) {
            p.update("A", mapOf("model" to "qwen3.8-27b"))
        }
        assertTrue(e.message!!.contains("Неизвестная модель: 'qwen3.8-27b'"), e.message)

        // ollama-модель принимается
        val rendered = p.update("A", mapOf("model" to "deepseek-r1:14b"))
        assertEquals("deepseek-r1:14b", rendered["model"])
        assertEquals(16384, rendered["contextLimit"], "контекст выведен из живого каталога")

        // maxTokens не может превышать контекстное окно ollama-модели (16384)
        val e2 = assertThrows(LlmSettingsValidationException::class.java) {
            p.update("A", mapOf("maxTokens" to 40000))
        }
        assertTrue(e2.message!!.contains("не может превышать контекстное окно модели (16384 токенов)"), e2.message)
    }

    @Test
    fun `session PUT with gpustack provider still rejects non catalog model`() {
        val store = JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("e.db")))
        val p = SessionLlmSettingsProvider(store, global(provider = "gpustack", model = "qwen3.8-27b"))

        val e = assertThrows(LlmSettingsValidationException::class.java) {
            p.update("A", mapOf("model" to "qwen2.5-coder:3b"))
        }
        assertTrue(e.message!!.contains("Неизвестная модель: 'qwen2.5-coder:3b'"), e.message)
    }
}
