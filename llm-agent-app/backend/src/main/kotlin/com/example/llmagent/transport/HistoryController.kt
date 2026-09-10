package com.example.llmagent.transport

import com.example.llmagent.agent.ChatMessage
import com.example.llmagent.agent.SessionStore
import com.example.llmagent.config.LlmProperties
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Восстановление и удаление истории диалога по sessionId. */
@RestController
class HistoryController(
    private val sessionStore: SessionStore,
    private val llm: LlmProperties,
) {

    data class HistoryMessage(
        val role: String,
        val content: String,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
    )

    /** Итоги по токенам и стоимости всей истории (формула стоимости — см. CONTRACT.md). */
    data class Totals(
        val promptTokens: Long,
        val completionTokens: Long,
        val costUsd: Double,
    )

    data class HistoryResponse(
        val sessionId: String,
        val messages: List<HistoryMessage>,
        val totals: Totals? = null,
    )

    data class DeleteResponse(val deleted: Boolean)

    @GetMapping("/api/sessions/{sessionId}/history")
    fun history(@PathVariable sessionId: String): HistoryResponse {
        val messages = sessionStore.get(sessionId)
        return HistoryResponse(
            sessionId,
            messages.map { HistoryMessage(it.role, it.content, it.promptTokens, it.completionTokens) },
            totals(messages),
        )
    }

    /**
     * Сумма токенов всех сообщений и условная стоимость по ТЕКУЩИМ тарифам настроек
     * ((prompt*priceInput + completion*priceOutput)/1M). null, если токенов нет ни у одного сообщения.
     */
    private fun totals(messages: List<ChatMessage>): Totals? {
        val hasTokens = messages.any { it.promptTokens != null || it.completionTokens != null }
        if (!hasTokens) return null
        val promptTokens = messages.sumOf { it.promptTokens?.toLong() ?: 0L }
        val completionTokens = messages.sumOf { it.completionTokens?.toLong() ?: 0L }
        val costUsd = (promptTokens * llm.priceInputPer1M + completionTokens * llm.priceOutputPer1M) / 1_000_000.0
        return Totals(promptTokens, completionTokens, costUsd)
    }

    /** Удаляет всю историю сессии (в том числе для несуществующей — всё равно 200). */
    @DeleteMapping("/api/sessions/{sessionId}")
    fun delete(@PathVariable sessionId: String): DeleteResponse {
        sessionStore.delete(sessionId)
        return DeleteResponse(true)
    }
}
