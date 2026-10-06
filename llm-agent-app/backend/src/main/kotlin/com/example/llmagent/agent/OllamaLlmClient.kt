package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.web.reactive.function.client.WebClient

/**
 * Клиент к локальной Ollama (OpenAI-совместимый /v1/chat/completions, провайдер «ollama»).
 * От базового [OpenAiChatClient] отличается:
 * - НЕТ Authorization-заголовка — локальная Ollama ключ не требует;
 * - НЕ шлёт провайдер-специфичные параметры: `chat_template_kwargs.enable_thinking`
 *   Ollama не понимает (лишние поля в теле — только риск 400 на строгих бэкендах).
 * Параметры запроса (model, temperature, top_p, top_k, max_tokens, timeout) читаются
 * из [LlmSettings] на каждый запрос — динамические изменения применяются без рестарта.
 */
class OllamaLlmClient(
    props: LlmProperties,
    om: ObjectMapper,
    settings: LlmSettings = LlmSettings.from(props),
) : OpenAiChatClient(om, settings) {

    override val webClient: WebClient = WebClient.builder()
        .baseUrl(normalizeBaseUrl(props.ollamaBaseUrl))
        .build()

    override fun applyProviderParams(body: ObjectNode, settings: LlmSettings) {
        // Специфичных параметров у Ollama нет.
    }
}
