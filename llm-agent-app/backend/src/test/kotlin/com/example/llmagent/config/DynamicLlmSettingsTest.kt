package com.example.llmagent.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Динамическая конфигурация LLM: каталог моделей, персистентность, валидация PUT-обновлений. */
class DynamicLlmSettingsTest {

    private val llm = LlmProperties() // default: model=default-coding, contextLimit=126608, maxTokens=10000

    private fun settings(
        store: AppSettingsStore = InMemoryStore(),
        props: LlmProperties = llm,
        modelEnabled: ModelEnabledStore? = null,
    ): DynamicLlmSettings = DynamicLlmSettings(props, store, modelEnabled)

    @Test
    fun `defaults come from properties when store is empty`() {
        val s = settings()
        assertEquals("default-coding", s.model())
        assertEquals(0.7, s.temperature())
        assertEquals(10000, s.maxTokens())
        // некаталоговая дефолтная модель — контекст из свойств
        assertEquals(126608, s.contextLimit())
    }

    @Test
    fun `default maxTokens is 10000 and exposed in settings map when no override exists`() {
        val s = settings()
        assertEquals(10000, s.maxTokens())
        assertEquals(10000, s.settings()["maxTokens"], "GET-карта должна отдавать дефолтный лимит")
    }

    @Test
    fun `context limit is derived from selected model per catalog`() {
        val s = settings()
        s.update(mapOf("model" to "qwen3.8-27b"))
        assertEquals(198 * 1024, s.contextLimit())
        s.update(mapOf("model" to "deepseek-v4-flash"))
        assertEquals(1024 * 1024, s.contextLimit())
        s.update(mapOf("model" to "glm-5.3-flash"))
        assertEquals(256 * 1024, s.contextLimit())
    }

    @Test
    fun `update persists changes and new instance on same store picks up saved rows`() {
        val store = InMemoryStore()
        val first = settings(store)
        first.update(
            mapOf(
                "model" to "glm-5.3-flash",
                "temperature" to 0.2,
                "maxTokens" to 300,
                "timeoutSeconds" to 42,
            )
        )

        // «Перезапуск backend»: новое хранилище поверх того же стора — настройки сохранились
        val second = DynamicLlmSettings(llm, store)
        assertEquals("glm-5.3-flash", second.model())
        assertEquals(256 * 1024, second.contextLimit())
        assertEquals(0.2, second.temperature())
        assertEquals(300, second.maxTokens())
        assertEquals(42L, second.timeoutSeconds())
    }

    @Test
    fun `provider change is ignored and current provider kept`() {
        val s = settings()
        s.update(mapOf("provider" to "gpustack", "temperature" to 0.5))
        // провайдер не изменился, остальные поля применились
        assertEquals("gpustack", s.provider())
        assertEquals(0.5, s.temperature())
    }

    @Test
    fun `unknown model is rejected and settings unchanged`() {
        val s = settings(props = LlmProperties(model = "qwen3.8-27b"))
        assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("model" to "no_such_model"))
        }
        assertEquals("qwen3.8-27b", s.model())
        assertEquals(198 * 1024, s.contextLimit())
    }

    @Test
    fun `negative temperature is rejected`() {
        val s = settings()
        assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("temperature" to -0.5))
        }
        assertEquals(0.7, s.temperature())
    }

    @Test
    fun `zero and negative maxTokens are rejected`() {
        val s = settings()
        assertThrows(LlmSettingsValidationException::class.java) { s.update(mapOf("maxTokens" to 0)) }
        assertThrows(LlmSettingsValidationException::class.java) { s.update(mapOf("maxTokens" to -7)) }
    }

    @Test
    fun `clearing maxTokens to null resets to configured default and survives restart`() {
        val store = InMemoryStore()
        // «сброс в null» возвращает к дефолту конфигурации, а не к «без лимита»
        val first = settings(store, LlmProperties(maxTokens = 128))
        first.update(mapOf("maxTokens" to null))
        assertEquals(128, first.maxTokens())
        assertEquals(128, first.settings()["maxTokens"], "сброс должен вернуть дефолт в GET-карту")

        val second = DynamicLlmSettings(LlmProperties(maxTokens = 128), store)
        assertEquals(128, second.maxTokens(), "сохранённый маркер null применяет дефолт при старте")
    }

    @Test
    fun `model change drives contextLimit in settings map`() {
        val s = settings()
        s.update(mapOf("model" to "deepseek-v4-flash"))
        val map = s.settings()
        assertEquals("deepseek-v4-flash", map["model"])
        assertEquals(1024 * 1024, map["contextLimit"])
    }

    @Test
    fun `reasoning is enabled by default and exposed in settings map`() {
        val s = settings()
        assertEquals(true, s.reasoningEnabled())
        assertEquals(true, s.settings()["reasoningEnabled"], "GET-карта должна отдавать reasoningEnabled")
    }

    @Test
    fun `reasoning default comes from properties`() {
        val s = settings(props = LlmProperties(reasoningEnabled = false))
        assertEquals(false, s.reasoningEnabled())
        assertEquals(false, s.settings()["reasoningEnabled"])
    }

    @Test
    fun `update reasoning to false and true persists and survives restart`() {
        val store = InMemoryStore()
        val first = settings(store)
        first.update(mapOf("reasoningEnabled" to false))
        assertEquals(false, first.reasoningEnabled())
        assertEquals("false", store.get("reasoningEnabled"), "отключение должно персиститься как 'false'")

        val second = DynamicLlmSettings(llm, store)
        assertEquals(false, second.reasoningEnabled(), "сохранённый 'false' применяется при старте")

        second.update(mapOf("reasoningEnabled" to true))
        val third = DynamicLlmSettings(llm, store)
        assertEquals(true, third.reasoningEnabled(), "сохранённый 'true' применяется при старте")
    }

    @Test
    fun `update reasoning absent leaves value unchanged`() {
        val s = settings()
        s.update(mapOf("temperature" to 0.3))
        assertEquals(true, s.reasoningEnabled(), "отсутствие reasoningEnabled не меняет значение")
    }

    @Test
    fun `clearing reasoning to null resets to configured default and survives restart`() {
        val store = InMemoryStore()
        val props = LlmProperties(reasoningEnabled = false)
        val first = settings(store, props)
        first.update(mapOf("reasoningEnabled" to true))
        // «сброс в null» — вернуть к дефолту конфигурации (false, задан в props)
        first.update(mapOf("reasoningEnabled" to null))
        assertEquals(false, first.reasoningEnabled())
        assertEquals(false, first.settings()["reasoningEnabled"], "сброс должен вернуть дефолт в GET-карту")
        assertEquals("null", store.get("reasoningEnabled"), "сброс персистится маркером 'null'")

        val second = DynamicLlmSettings(props, store)
        assertEquals(false, second.reasoningEnabled(), "сохранённый маркер 'null' применяет дефолт при старте")
    }

    @Test
    fun `unparsable persisted reasoning falls back to default`() {
        val store = InMemoryStore()
        store.save("reasoningEnabled", "not-a-bool")
        val s = settings(store)
        assertEquals(true, s.reasoningEnabled(), "нераспознанное значение → дефолт (true)")
    }

    @Test
    fun `non boolean reasoning value is rejected`() {
        val s = settings()
        assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("reasoningEnabled" to "yes"))
        }
        assertEquals(true, s.reasoningEnabled())
    }

    // --- Каталог: включение/отключение моделей ---

    @Test
    fun `enabled model stays selectable when enabled store is present`() {
        val s = settings(modelEnabled = InMemoryModelEnabledStore())
        s.update(mapOf("model" to "qwen3.8-27b"))
        assertEquals("qwen3.8-27b", s.model())
    }

    @Test
    fun `disabled model is rejected with catalog message and settings unchanged`() {
        val modelEnabled = InMemoryModelEnabledStore(enabled = setOf("qwen3.8-27b", "glm-5.3-flash"))
        val s = settings(props = LlmProperties(model = "qwen3.8-27b"), modelEnabled = modelEnabled)

        val e = assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("model" to "deepseek-v4-flash"))
        }
        assertTrue(e.message!!.contains("отключена в каталоге: deepseek-v4-flash"))
        assertEquals("qwen3.8-27b", s.model(), "модель не должна измениться после отказа")
    }

    @Test
    fun `unknown model still yields unknown-model error even when disabled marked`() {
        val modelEnabled = InMemoryModelEnabledStore()
        val s = settings(modelEnabled = modelEnabled)
        val e = assertThrows(LlmSettingsValidationException::class.java) {
            s.update(mapOf("model" to "no_such_model"))
        }
        assertTrue(e.message!!.contains("Неизвестная модель"), e.message)
        assertTrue(!e.message!!.contains("отключена"), e.message)
    }

    @Test
    fun `persisted model that got disabled is skipped on restart`() {
        val store = InMemoryStore()
        store.save("model", "deepseek-v4-flash")
        val modelEnabled = InMemoryModelEnabledStore(enabled = setOf("qwen3.8-27b", "glm-5.3-flash"))

        val s = DynamicLlmSettings(llm, store, modelEnabled)
        assertEquals("default-coding", s.model(), "отключённая сохранённая модель не применяется — остаёмся на дефолте")
    }

    @Test
    fun `persisted disabled model clears enabled flag selection via update still works`() {
        val store = InMemoryStore()
        val modelEnabled = InMemoryModelEnabledStore()
        val first = DynamicLlmSettings(llm, store, modelEnabled)
        first.update(mapOf("model" to "glm-5.3-flash"))
        assertEquals("glm-5.3-flash", first.model())

        // модель отключаем → новый инстанс (рестарт) на неё не возвращается
        modelEnabled.enabled = setOf("qwen3.8-27b", "deepseek-v4-flash")
        val second = DynamicLlmSettings(llm, store, modelEnabled)
        assertEquals("default-coding", second.model())
    }

    private class InMemoryStore : AppSettingsStore {
        private val map = mutableMapOf<String, String>()
        override fun save(key: String, value: String) {
            map[key] = value
        }
        override fun get(key: String): String? = map[key]
        override fun all(): Map<String, String> = map.toMap()
    }

    private class InMemoryModelEnabledStore(
        var enabled: Set<String> = LlmCatalog.MODELS.map { it.id }.toSet(),
    ) : ModelEnabledStore {
        override fun setEnabled(id: String, enabled: Boolean) {
            this.enabled = if (enabled) this.enabled + id else this.enabled - id
        }
        override fun isEnabled(id: String): Boolean = id in enabled
        override fun all(): Map<String, Boolean> = enabled.associateWith { true }
    }
}
