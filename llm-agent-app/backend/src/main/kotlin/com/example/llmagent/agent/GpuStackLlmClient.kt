package com.example.llmagent.agent

import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient

/**
 * Клиент к GPUStack (OpenAI-совместимый /v1/chat/completions) со стримингом токенов.
 * Ключ передаётся только как Bearer-заголовок из LlmProperties (env), не логируется.
 * Вся механика стрима (сборка тела, SSE-разбор, финальные события) — в базовом
 * [OpenAiChatClient]; здесь только специфика провайдера: Bearer-ключ и параметр
 * `chat_template_kwargs.enable_thinking` (выключение «рассуждений»).
 * Параметры запроса (model, temperature, top_p, top_k, max_tokens, timeout) читаются
 * из [LlmSettings] на каждый запрос — динамические изменения применяются без рестарта.
 */
class GpuStackLlmClient(
    private val props: LlmProperties,
    om: ObjectMapper,
    settings: LlmSettings = LlmSettings.from(props),
) : OpenAiChatClient(om, settings) {

    override val webClient: WebClient = WebClient.builder()
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${props.apiKey}")
        .baseUrl(normalizeBaseUrl(props.baseUrl))
        .build()

    override fun applyProviderParams(body: ObjectNode, settings: LlmSettings) {
        // Выключаем «рассуждение» модели (thinking): vLLM понимает chat_template_kwargs.enable_thinking=false
        // (проверено для qwen3.8-27b / deepseek-v4-flash). Для glm* thinking форсирован — kwarg НЕ уходит,
        // иначе текст рассуждений протекает в content. Включено — ничего не шлём (thinking включён по умолчанию).
        if (!settings.reasoningEnabled() && !settings.model().startsWith("glm", ignoreCase = true)) {
            body.putObject("chat_template_kwargs").put("enable_thinking", false)
        }
    }
}

/**
 * Ошибка LLM API с HTTP-статусом (к примеру 4xx) — перехватывается в AgentImpl
 * и превращается в понятное error-событие («Ошибка LLM API (HTTP <status>): <message>»).
 */
class LlmApiException(val status: Int, message: String) : RuntimeException(message)
