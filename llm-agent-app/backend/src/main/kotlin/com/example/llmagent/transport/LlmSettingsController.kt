package com.example.llmagent.transport

import com.example.llmagent.config.DynamicLlmSettings
import com.example.llmagent.config.LlmSettingsProvider
import com.example.llmagent.config.LlmSettingsValidationException
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
 * GET: текущие настройки (provider, model, производный contextLimit, temperature и пр.).
 * PUT: частичное обновление; provider неизменяем (изменения игнорируются), model обязана быть
 * в каталоге, contextLimit пересчитывается по каталогу; reasoningEnabled — boolean (null — сброс
 * к дефолту true); некорректные значения → HTTP 400.
 * Изменения персистятся в `app_settings` и переживают перезапуск backend.
 */
@RestController
class LlmSettingsController(
    private val settingsProvider: LlmSettingsProvider,
    private val dynamicSettings: DynamicLlmSettings,
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
}
