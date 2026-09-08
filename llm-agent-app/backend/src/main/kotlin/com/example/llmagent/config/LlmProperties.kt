package com.example.llmagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Конфигурация LLM. Значения задаются ТОЛЬКО через переменные окружения
 * (см. application.yml). Ключи и URL GPUStack не хардкодятся в коде.
 */
@ConfigurationProperties(prefix = "llm")
data class LlmProperties(
    /** mock | gpustack */
    val provider: String = "mock",
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
    /** Лимит выходных токенов (max_tokens); null/<=0 — параметр не отправляется. */
    val maxTokens: Int? = null,
    val timeoutSeconds: Long = 60,
)

@ConfigurationProperties(prefix = "agent")
data class AgentProperties(
    /** Максимальное число итераций LLM в одном run (лимитирует цикл tool-calling). */
    val maxToolCallIterations: Int = 8,
)
