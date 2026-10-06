package com.example.llmagent.agent

import com.example.llmagent.config.LlmProviders
import com.example.llmagent.config.LlmSettings
import reactor.core.publisher.Flux

/**
 * Маршрутизирующий клиент: направляет каждый вызов к клиенту провайдера, выбранного
 * В НАСТРОЙКАХ ЭТОГО ВЫЗОВА ([LlmSettings.provider]) — «gpustack» или «ollama».
 * Переключение провайдера в рантайме (PUT /api/llm-settings) не требует рестарта:
 * следующие вызовы идут уже к новому клиенту.
 */
class RoutingLlmClient(
    private val gpuStack: LlmClient,
    private val ollama: LlmClient,
) : LlmClient {

    /** Клиент провайдера для данных настроек (всё неизвестное трактуется как gpustack). */
    internal fun delegate(settings: LlmSettings): LlmClient =
        if (settings.provider() == LlmProviders.OLLAMA) ollama else gpuStack

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> = delegate(settings).streamChat(messages, tools, settings)

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
        onRequestBody: (String) -> Unit,
    ): Flux<LlmEvent> = delegate(settings).streamChat(messages, tools, settings, onRequestBody)
}
