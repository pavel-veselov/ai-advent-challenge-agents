package com.example.llmagent.agent

import com.example.llmagent.config.LlmSettings
import reactor.core.publisher.Flux

/** События, порождаемые одним вызовом LLM в режиме стриминга. */
sealed interface LlmEvent {
    data class ContentDelta(val delta: String) : LlmEvent
    data class ToolCallsComplete(val toolCalls: List<LlmToolCall>) : LlmEvent
    data class Finished(val finishReason: String, val usage: LlmUsage? = null) : LlmEvent

    /**
     * Собранное из стрима тело ответа API в привычном нестримовом виде (chat.completion,
     * pretty JSON) — финальное событие стрима, для панели «Детализация ответа».
     */
    data class ResponseAssembled(val body: String) : LlmEvent
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

    /**
     * То же, но с колбэком на фактическое тело HTTP-запроса (pretty JSON). Колбэк вызывается
     * синхронно при построении запроса, ДО HTTP-вызова. Реализации без захвата наследуют
     * дефолт, который игнорирует колбэк.
     */
    fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
        onRequestBody: (String) -> Unit,
    ): Flux<LlmEvent> = streamChat(messages, tools, settings)
}
