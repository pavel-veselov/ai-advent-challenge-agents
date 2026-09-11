package com.example.llmagent.transport

import com.example.llmagent.agent.CompressionSettings
import com.example.llmagent.agent.CompressionSettingsValidationException
import com.example.llmagent.agent.SessionCompressionStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Per-session настройки сжатия истории (GET/PUT /api/sessions/{sessionId}/compression).
 *
 * GET — текущее состояние: `{ "sessionId", "enabled", "keepLast", "summaryEvery" }`
 * (строки нет → значения по умолчанию: enabled=false, keepLast=5, summaryEvery=10).
 * PUT — частичное обновление (ответ — те же настройки после применения):
 * отсутствующие в теле поля не меняются; валидация: `enabled` — boolean, `keepLast` 1..50,
 * `summaryEvery` 2..100 → невалидное значение 400. Неизвестная сессия → 404 НЕ выбрасывается
 * (настройки применяются, когда сессия начнёт диалог). Настройки персистятся
 * в SQLite (session_compression) и переживают перезапуск backend.
 */
@RestController
class CompressionController(
    private val compressionStore: SessionCompressionStore,
) {

    @GetMapping("/api/sessions/{sessionId}/compression")
    fun get(@PathVariable sessionId: String): CompressionSettings =
        compressionStore.getSettings(sessionId)

    @PutMapping("/api/sessions/{sessionId}/compression")
    fun update(@PathVariable sessionId: String, @RequestBody patch: Map<String, Any?>): CompressionSettings =
        try {
            compressionStore.updateSettings(sessionId, patch)
        } catch (e: CompressionSettingsValidationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
}
