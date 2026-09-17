package com.example.llmagent.transport

import com.example.llmagent.agent.ChatMessage
import com.example.llmagent.agent.SessionBranchStore
import com.example.llmagent.agent.SessionCompressionStore
import com.example.llmagent.agent.SessionContextStore
import com.example.llmagent.agent.SessionFactsStore
import com.example.llmagent.agent.SessionStore
import com.example.llmagent.agent.TaskStateStore
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.SessionLlmSettingsStore
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Восстановление и удаление истории диалога по sessionId. */
@RestController
class HistoryController(
    private val sessionStore: SessionStore,
    private val compressionStore: SessionCompressionStore,
    private val llm: LlmProperties,
    private val sessionLlmSettingsStore: SessionLlmSettingsStore,
    private val contextStore: SessionContextStore,
    private val factsStore: SessionFactsStore,
    private val branchStore: SessionBranchStore,
    private val taskStateStore: TaskStateStore,
) {

    data class HistoryMessage(
        /** Идентификатор сообщения в chat_messages; null — сообщение без id (устаревшие данные). */
        val id: Long? = null,
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
        val strategy = contextStore.resolve(sessionId, compressionStore.getSettings(sessionId).enabled)
        val branches = branchStore.list(sessionId)
        val messages: List<ChatMessage> =
            if (strategy == SessionContextStore.STRATEGY_BRANCHING && branches.isNotEmpty()) {
                // Branching: история — цепочка АКТИВНОЙ ветки (корень → голова), не-системные
                // сообщения в хронологическом порядке. Общие предки до точки fork входят в цепочку.
                val branch = branchStore.getActive(sessionId) ?: branches.first()
                val allById = sessionStore.get(sessionId).associateBy { it.id }
                val chain = branch.headMessageId?.let { sessionStore.getBranchChain(sessionId, it) } ?: emptyList()
                chain
                    .filter { it.role != "system" }
                    .mapNotNull { m -> allById[m.id] }
            } else {
                // Legacy-поведение: все сообщения сессии в порядке вставки (ORDER BY id),
                // включая системные заметки (о сжатии и т.п.).
                sessionStore.get(sessionId)
            }
        return HistoryResponse(
            sessionId,
            messages.map { HistoryMessage(it.id, it.role, it.content, it.promptTokens, it.completionTokens) },
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

    /**
     * Удаляет всю историю сессии (в том числе для несуществующей — всё равно 200) вместе
     * со ВСЕМИ per-session данными: сжатие (настройки + резюме), настройки LLM, стратегия
     * контекста, «липкие факты», ветки и состояние задачи (task_state, Day-13) — чтобы
     * после пересоздания сессии не оставалось «призрачных» настроек. Рабочая память (WM)
     * с day-12 живёт НА ПРОЕКТЕ (см. WorkingMemoryStore) — удаление ОДНОЙ сессии её НЕ
     * трогает (она общая для всех сессий проекта); проектная WM каскадно удаляется при
     * DELETE /api/projects/{id}. Долговременная память (LongTermMemoryStore) ГЛОБАЛЬНАЯ —
     * её удаление сессии НЕ трогает (записи переживают удаление сессий).
     */
    @DeleteMapping("/api/sessions/{sessionId}")
    fun delete(@PathVariable sessionId: String): DeleteResponse {
        sessionStore.delete(sessionId)
        compressionStore.remove(sessionId)
        sessionLlmSettingsStore.remove(sessionId)
        contextStore.remove(sessionId)
        factsStore.remove(sessionId)
        branchStore.remove(sessionId)
        taskStateStore.remove(sessionId)
        return DeleteResponse(true)
    }
}
