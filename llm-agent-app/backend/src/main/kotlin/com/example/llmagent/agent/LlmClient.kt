package com.example.llmagent.agent

import com.example.llmagent.config.LlmSettings
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
 * Транспорт к LLM — GPUStack (реальная интеграция; mock удалён).
 * Один вызов = один ответ модели в режиме стриминга.
 *
 * [settings] — применённые настройки ИМЕННО этого вызова (per-session в чат-потоке):
 * клиент берёт из них model/temperature/maxTokens/reasoningEnabled/timeout на каждый запрос.
 */
interface LlmClient {
    fun streamChat(messages: List<LlmMessage>, tools: List<ToolDefinition>, settings: LlmSettings): Flux<LlmEvent>
}
