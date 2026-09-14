package com.example.llmagent.transport

import com.example.llmagent.agent.ContextStrategySettings
import com.example.llmagent.agent.ContextStrategyValidationException
import com.example.llmagent.agent.SessionBranchStore
import com.example.llmagent.agent.SessionCompressionStore
import com.example.llmagent.agent.SessionContextStore
import com.example.llmagent.agent.SessionStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Per-session стратегия контекста (GET/PUT /api/sessions/{sessionId}/context-strategy).
 *
 * GET — текущее состояние: `{ "sessionId", "strategy", "windowSize", "activeBranchId" }`
 * (строки нет → strategy='none', windowSize=12, activeBranchId=null). `activeBranchId` —
 * ид активной ветки (branching, см. /branches), служебное поле полного состояния.
 *
 * PUT — частичное обновление (ответ — полное состояние после применения): отсутствующие
 * в теле поля не меняются; валидация: `strategy` — одна из `none|sliding_window|
 * sticky_facts|summary|branching`, `windowSize` 1..50 → невалидное значение 400.
 * Неизвестная сессия → 404 НЕ выбрасывается (настройки применятся, когда сессия начнёт диалог).
 *
 * Побочные эффекты стратегии (синхронизация с legacy-сжатием и ветками):
 * - `summary` → включается legacy-сжатие: `compression.enabled=true` (стратегия summary работает
 *   через существующий механизм сжатия);
 * - любая другая стратегия → `compression.enabled=false` (чтобы legacy-сжатие не включалось
 *   неявно через правило разрешения стратегии);
 * - `branching` → плюс ленивое подключение веток: линейный бэккафилл parent_id уже накопленной
 *   истории и ветка «Основная» (подробности в SessionBranchStore/SessionStore).
 * Настройки персистятся в SQLite (session_context_strategy) и переживают перезапуск backend.
 */
@RestController
class ContextStrategyController(
    private val contextStore: SessionContextStore,
    private val compressionStore: SessionCompressionStore,
    private val branchStore: SessionBranchStore,
    private val sessionStore: SessionStore,
) {

    @GetMapping("/api/sessions/{sessionId}/context-strategy")
    fun get(@PathVariable sessionId: String): ContextStrategySettings =
        contextStore.get(sessionId)

    @PutMapping("/api/sessions/{sessionId}/context-strategy")
    fun update(@PathVariable sessionId: String, @RequestBody patch: Map<String, Any?>): ContextStrategySettings {
        val updated = try {
            contextStore.update(sessionId, patch)
        } catch (e: ContextStrategyValidationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        // Побочные эффекты стратегии (см. KDoc класса).
        when (updated.strategy) {
            SessionContextStore.STRATEGY_SUMMARY -> {
                compressionStore.updateSettings(sessionId, mapOf("enabled" to true))
            }
            SessionContextStore.STRATEGY_BRANCHING -> {
                compressionStore.updateSettings(sessionId, mapOf("enabled" to false))
                // Ленивое подключение веток: бэккафилл + ветка по умолчанию «Основная».
                sessionStore.backfillLinearParents(sessionId)
                branchStore.ensureDefault(
                    sessionId,
                    sessionStore.getStored(sessionId).lastOrNull { it.role != "system" }?.id,
                )
            }
            else -> {
                compressionStore.updateSettings(sessionId, mapOf("enabled" to false))
            }
        }
        return contextStore.get(sessionId)
    }
}
