package com.example.llmagent.transport

import com.example.llmagent.config.LlmSettingsValidationException
import com.example.llmagent.config.SessionLlmSettingsProvider
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Per-session настройки LLM (GET/PUT /api/sessions/{sessionId}/llm-settings).
 *
 * GET — полное ЭФФЕКТИВНОЕ состояние: `{ model, contextLimit, temperature, topP, topK,
 * maxTokens, timeoutSeconds, priceInputPer1M, priceOutputPer1M, reasoningEnabled }`
 * (те же поля, что у глобального /api/llm-settings, БЕЗ provider).
 * Строки настроек у сессии нет → возвращаются ТЕКУЩИЕ ГЛОБАЛЬНЫЕ значения (app_settings/
 * defaults) БЕЗ их сохранения.
 * PUT — частичное обновление: отсутствующие в теле поля не меняются; `null` у любого поля =
 * снять переопределение сессии (эффективно применяется ТЕКУЩЕЕ глобальное значение);
 * валидация — как в глобальном PUT (модель в каталоге И включённая, числовые поля — те же
 * проверки) → невалидное значение 400.
 * Настройки персистятся в SQLite (session_llm_settings) и переживают перезапуск backend.
 * Сессия может ещё не иметь сообщений (создана кнопкой «+» на фронтенде): GET вернёт
 * глобальный эффективный набор, PUT сохранит переопределения — они применятся, как только
 * сессия начнёт диалог. При удалении сессии настройки чистятся (HistoryController.delete).
 */
@RestController
class SessionLlmSettingsController(
    private val sessionLlmSettings: SessionLlmSettingsProvider,
) {

    @GetMapping("/api/sessions/{sessionId}/llm-settings")
    fun get(@PathVariable sessionId: String): Map<String, Any?> =
        sessionLlmSettings.get(sessionId)

    @PutMapping("/api/sessions/{sessionId}/llm-settings")
    fun update(@PathVariable sessionId: String, @RequestBody patch: Map<String, Any?>): Map<String, Any?> =
        try {
            sessionLlmSettings.update(sessionId, patch)
        } catch (e: LlmSettingsValidationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
}
