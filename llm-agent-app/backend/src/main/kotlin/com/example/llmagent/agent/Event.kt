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

data class ErrorEvent(val idx: Int, val message: String) : AgentEvent {
    override val type = "error"
    override val stepId = "error-$idx"
    override val payload = mapOf("message" to message)
}
