package com.example.llmagent.transport

import com.example.llmagent.config.DynamicLlmSettings
import com.example.llmagent.config.LlmCatalog
import com.example.llmagent.config.LlmProviders
import com.example.llmagent.config.LlmSettingsProvider
import com.example.llmagent.config.LlmSettingsValidationException
import com.example.llmagent.config.OllamaDiscovery
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Применённые настройки LLM — фронтенд забирает их при открытии страницы, до первого запроса,
 * и изменяет на лету через PUT БЕЗ перезапуска backend.
 *
 * GET /api/llm-settings: текущие настройки (provider, model, производный contextLimit,
 * temperature и пр.).
 * PUT /api/llm-settings: частичное обновление; provider — runtime-переключение
 * («gpustack»/«ollama», НЕ персистится; при переключении глобальная модель сбрасывается
 * на первую модель нового провайдера), model обязана быть в каталоге ТЕКУЩЕГО провайдера,
 * contextLimit пересчитывается; некорректные значения → HTTP 400.
 * Изменения персистятся в `app_settings` и переживают перезапуск backend.
 *
 * GET /api/llm/providers: каталог провайдеров для переключения — текущий провайдер и
 * список провайдеров с моделями каждого (gpustack — [LlmCatalog]; ollama — живое
 * обнаружение, при недоступности Ollama список моделей пуст). Всегда 200.
 */
@RestController
class LlmSettingsController(
    private val settingsProvider: LlmSettingsProvider,
    private val dynamicSettings: DynamicLlmSettings,
    private val ollama: OllamaDiscovery,
) {

    @GetMapping("/api/llm-settings")
    fun llmSettings(): Map<String, Any?> = settingsProvider.settings()

    @PutMapping("/api/llm-settings")
    fun updateLlmSettings(@RequestBody patch: Map<String, Any?>): Map<String, Any?> {
        try {
            dynamicSettings.update(patch)
        } catch (e: LlmSettingsValidationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return settingsProvider.settings()
    }

    /** Каталог провайдеров LLM (для селектора провайдера во фронтенде). Всегда 200. */
    @GetMapping("/api/llm/providers")
    fun llmProviders(): Map<String, Any?> = mapOf(
        "current" to dynamicSettings.provider(),
        "providers" to LlmProviders.ALL.map { id ->
            mapOf(
                "id" to id,
                "label" to LlmProviders.label(id),
                "models" to when (id) {
                    LlmProviders.OLLAMA -> ollama.models().map { m ->
                        mapOf(
                            "id" to m.id,
                            "contextLimit" to m.contextLimit,
                            "description" to m.description,
                        )
                    }
                    else -> LlmCatalog.MODELS.map { spec ->
                        mapOf(
                            "id" to spec.id,
                            "contextLimit" to spec.contextWindow,
                            "description" to spec.description,
                        )
                    }
                },
            )
        },
    )
}
