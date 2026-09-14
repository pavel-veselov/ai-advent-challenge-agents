package com.example.llmagent.transport

import com.example.llmagent.agent.SessionFactsStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Ответ GET /api/sessions/{sessionId}/facts. */
data class FactsResponse(
    val sessionId: String,
    /** Факты в порядке вставки (ключ-значение); пусто — фактов ещё нет. */
    val facts: Map<String, String>,
)

/**
 * «Липкие факты» сессии (GET /api/sessions/{sessionId}/facts).
 *
 * Факты (ключ-значение) извлекаются LLM при стратегии context-strategy `sticky_facts`
 * (см. AgentImpl, событие `facts_updated` в SSE-потоке) и подмешиваются в контекст
 * системным сообщением. Здесь — только чтение текущего набора; обновляет их сам агент.
 * Неизвестная сессия → 404 НЕ выбрасывается: GET отдаёт пустой набор (фактов ещё нет).
 * Факты персистятся в SQLite (session_facts) и переживают перезапуск backend.
 */
@RestController
class FactsController(private val factsStore: SessionFactsStore) {

    @GetMapping("/api/sessions/{sessionId}/facts")
    fun get(@PathVariable sessionId: String): FactsResponse =
        FactsResponse(sessionId, factsStore.getAll(sessionId))
}
