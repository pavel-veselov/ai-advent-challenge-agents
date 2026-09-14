package com.example.llmagent.agent

/**
 * Одно событие агента в терминах контракта CONTRACT.md.
 * Транспорт (контроллер) добавляет служебные поля runId / timestamp / sequence.
 */
sealed interface AgentEvent {
    val type: String
    val stepId: String
    val payload: Map<String, Any?>
}

data class AgentStarted(
    val userMessage: String,
    val settings: Map<String, Any?>,
) : AgentEvent {
    override val type = "agent_started"
    override val stepId = "user"
    override val payload = mapOf("userMessage" to userMessage, "settings" to settings)
}

data class LlmRequestStarted(
    val iteration: Int,
    val prompt: List<Map<String, String>>,
    /** Оценки токенов больше нет (локальный подсчёт удалён); поле 0 — сохранено для схемы. */
    val estimatedRequestTokens: Int = 0,
) : AgentEvent {
    override val type = "llm_request_started"
    override val stepId = "llm-$iteration"
    override val payload = mapOf(
        "iteration" to iteration,
        "prompt" to prompt,
        "estimatedRequestTokens" to estimatedRequestTokens,
    )
}

data class LlmToken(val iteration: Int, val delta: String) : AgentEvent {
    override val type = "llm_token"
    override val stepId = "llm-$iteration"
    override val payload = mapOf("delta" to delta)
}

data class LlmResponseFinished(
    val iteration: Int,
    val finishReason: String,
    val usage: LlmUsage? = null,
    /** Локальная оценка токенов удалена; поле всегда null — сохранено для схемы. */
    val estimatedRequestTokens: Int? = null,
    /** Условная стоимость запроса+ответа в USD; null, если usage от провайдера не пришёл. */
    val costUsd: Double? = null,
) : AgentEvent {
    override val type = "llm_response_finished"
    override val stepId = "llm-$iteration"
    override val payload: Map<String, Any?> = buildMap {
        put("finishReason", finishReason)
        put("estimatedRequestTokens", estimatedRequestTokens)
        if (costUsd != null) put("costUsd", costUsd)
        if (usage != null) put("usage", mapOf("inputTokens" to usage.inputTokens, "outputTokens" to usage.outputTokens))
    }
}

data class ToolCallStarted(val toolName: String, val idx: Int, val args: Map<String, Any?>) : AgentEvent {
    override val type = "tool_call_started"
    override val stepId = "tool-$toolName-$idx"
    override val payload = mapOf("toolName" to toolName, "args" to args)
}

data class ToolCallFinished(val toolName: String, val idx: Int, val status: String, val result: String) : AgentEvent {
    override val type = "tool_call_finished"
    override val stepId = "tool-$toolName-$idx"
    override val payload = mapOf("result" to result, "status" to status)
}

data class AgentFinished(val finalText: String) : AgentEvent {
    override val type = "agent_finished"
    override val stepId = "answer"
    override val payload = mapOf("finalText" to finalText)
}

/**
 * Начало вызова LLM для сжатия истории (per-session). Идёт ДО основного цикла,
 * поэтому НЕ входит в нумерацию итераций (stepId фиксирован, без `<iteration>`).
 * `prompt` — снимок промпта вызова резюмирования: что именно ушло в LLM
 * (тот же формат, что у llm_request_started).
 */
data class ContextSummaryStarted(
    val foldCount: Int,
    val prompt: List<Map<String, String>>,
) : AgentEvent {
    override val type = "context_summary_started"
    override val stepId = "context-summary"
    override val payload = mapOf("foldCount" to foldCount, "prompt" to prompt)
}

/**
 * Завершение вызова LLM для сжатия истории: токены из usage API (0, если провайдер
 * их не прислал), текст резюме, который вернула LLM и который сохранён в сессии,
 * и эвристическая оценка размера контекста ДО сжатия и ПОСЛЕ (токены, см.
 * AgentImpl.estimateTokens).
 */
data class ContextSummaryFinished(
    val foldCount: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val summary: String,
    val contextTokensBefore: Int,
    val contextTokensAfter: Int,
) : AgentEvent {
    override val type = "context_summary_finished"
    override val stepId = "context-summary"
    override val payload = mapOf(
        "foldCount" to foldCount,
        "promptTokens" to promptTokens,
        "completionTokens" to completionTokens,
        "summary" to summary,
        "contextTokensBefore" to contextTokensBefore,
        "contextTokensAfter" to contextTokensAfter,
    )
}

data class ErrorEvent(val idx: Int, val message: String) : AgentEvent {
    override val type = "error"
    override val stepId = "error-$idx"
    override val payload = mapOf("message" to message)
}

/**
 * Обновление «липких фактов» сессии (strategy=sticky_facts): факты только что извлечены
 * LLM, сохранены и в текущем run подмешаны в контекст. Идёт ДО основного цикла (как
 * контекстные events сжатия), поэтому НЕ входит в нумерацию итераций: stepId фиксирован
 * ("facts", вне numbering context_summary_*). Полезной нагрузки минимум — поля токенов
 * намеренно нет: факты нужны только для контекста, а не для метрик.
 */
data class FactsUpdated(val facts: Map<String, String>) : AgentEvent {
    override val type = "facts_updated"
    override val stepId = "facts"
    override val payload = mapOf("facts" to facts)
}
