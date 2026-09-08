package com.example.llmagent.agent

import reactor.core.publisher.Flux

/** События, порождаемые одним вызовом LLM в режиме стриминга. */
sealed interface LlmEvent {
    data class ContentDelta(val delta: String) : LlmEvent
    data class ToolCallsComplete(val toolCalls: List<LlmToolCall>) : LlmEvent
    data class Finished(val finishReason: String, val usage: LlmUsage? = null) : LlmEvent
}

/**
 * Статистика токенов из финального чанка OpenAI-совместимого API (usage).
 * Доступна только если провайдер вернул её (запрашиваем через stream_options.include_usage).
 */
data class LlmUsage(
    val inputTokens: Int,
    val outputTokens: Int,
)

/**
 * Транспорт к LLM (mock или GPUStack).
 * Один вызов = один ответ модели в режиме стриминга.
 */
interface LlmClient {
    fun streamChat(messages: List<LlmMessage>, tools: List<ToolDefinition>): Flux<LlmEvent>
}
