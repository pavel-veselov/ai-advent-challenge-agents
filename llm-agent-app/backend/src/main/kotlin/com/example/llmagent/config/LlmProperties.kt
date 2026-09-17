package com.example.llmagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Конфигурация LLM. Значения задаются ТОЛЬКО через переменные окружения
 * (см. application.yml). Ключи и URL GPUStack не хардкодятся в коде.
 */
@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    /** Провайдер LLM — фиксирован на реальной интеграции GPUStack (mock удалён). */
    val provider: String = "gpustack",
    /** Базовый адрес GPUStack-сервера, например https://<GPUStack-URL> (без /v1). */
    val baseUrl: String = "",
    /** Bearer-ключ GPUStack. Никогда не выводить в логи. */
    val apiKey: String = "",
    /** Идентификатор модели. */
    val model: String = "default-coding",
    val temperature: Double = 0.7,
    /** Nucleus sampling (top_p), уходит в API всегда. */
    val topP: Double = 1.0,
    /** Top-k sampling; null/<=0 — параметр не отправляется (не все бэкенды его принимают). */
    val topK: Int? = null,
    /** Лимит выходных токенов (max_tokens) — по умолчанию 10000; null/<=0 — параметр не отправляется. */
    val maxTokens: Int? = 10000,
    /** Включено ли «рассуждение» модели (thinking). false — в API уходит chat_template_kwargs.enable_thinking=false (не для glm*). */
    val reasoningEnabled: Boolean = true,
    val timeoutSeconds: Long = 7200,
    /** Лимит контекста модели в токенах — для пресечения переполнения до отправки запроса. */
    val contextLimit: Int = 126608,
    /** Условная цена за 1M входных токенов, USD (для расчёта стоимости ответа). */
    val priceInputPer1M: Double = 0.1,
    /** Условная цена за 1M выходных токенов, USD (для расчёта стоимости ответа). */
    val priceOutputPer1M: Double = 0.1,
)

@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    /** Максимальное число итераций LLM в одном run (лимитирует цикл tool-calling). */
    val maxToolCallIterations: Int = 8,
)
