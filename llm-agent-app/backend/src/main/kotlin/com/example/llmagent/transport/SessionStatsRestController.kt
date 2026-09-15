package com.example.llmagent.transport

import com.example.llmagent.agent.SessionAggregate
import com.example.llmagent.agent.SessionStore
import com.example.llmagent.config.LlmProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Агрегированная статистика по сессиям: список сессий с суммами токенов и условной
 * стоимостью, а также глобальные итоги по всем сессиям. Стоимость считается по
 * ТЕКУЩИМ тарифам настроек (см. costUsd в CONTRACT.md).
 */
@RestController
class SessionStatsRestController(
    private val sessionStore: SessionStore,
    private val llm: LlmProperties,
) {

    data class SessionsResponse(val sessions: List<SessionSummary>)

    data class SessionSummary(
        val sessionId: String,
        val messageCount: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val costUsd: Double,
        val lastActivity: String,
        val firstUserMessage: String? = null,
        /** id проекта сессии (day-12); -1 — сессия без строки в chat_sessions (легаси/осиротевшая). */
        val projectId: Long = -1,
    )

    data class StatsResponse(
        val sessionCount: Long,
        val messageCount: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val costUsd: Double,
        /** Кумулятивные счётчики «за всё время» — переживают удаление сессий. */
        val lifetime: LifetimeSummary,
    )

    data class LifetimeSummary(
        val sessions: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val costUsd: Double,
    )

    @GetMapping("/api/sessions")
    fun sessions(): SessionsResponse =
        SessionsResponse(sessionStore.listSessionAggregates().map { summary(it) })

    @GetMapping("/api/stats")
    fun stats(): StatsResponse {
        val aggregates = sessionStore.listSessionAggregates()
        val sessionCount = aggregates.size.toLong()
        val messageCount = aggregates.sumOf { it.messageCount }
        val promptTokens = aggregates.sumOf { it.promptTokens }
        val completionTokens = aggregates.sumOf { it.completionTokens }
        val lifetime = sessionStore.getLifetimeStats()
        return StatsResponse(
            sessionCount = sessionCount,
            messageCount = messageCount,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            costUsd = costUsd(promptTokens, completionTokens),
            lifetime = LifetimeSummary(
                sessions = lifetime.sessionsTotal,
                promptTokens = lifetime.promptTokensTotal,
                completionTokens = lifetime.completionTokensTotal,
                totalTokens = lifetime.promptTokensTotal + lifetime.completionTokensTotal,
                costUsd = lifetime.costUsdTotal,
            ),
        )
    }

    private fun summary(a: SessionAggregate): SessionSummary =
        SessionSummary(
            sessionId = a.sessionId,
            messageCount = a.messageCount,
            promptTokens = a.promptTokens,
            completionTokens = a.completionTokens,
            costUsd = costUsd(a.promptTokens, a.completionTokens),
            lastActivity = a.lastActivity,
            firstUserMessage = a.firstUserMessage,
            projectId = a.projectId,
        )

    /** (prompt*priceInput + completion*priceOutput) / 1M; с нулевыми токенами — 0.0. */
    private fun costUsd(promptTokens: Long, completionTokens: Long): Double {
        val p = promptTokens * llm.priceInputPer1M
        val c = completionTokens * llm.priceOutputPer1M
        return (p + c) / 1_000_000.0
    }
}
