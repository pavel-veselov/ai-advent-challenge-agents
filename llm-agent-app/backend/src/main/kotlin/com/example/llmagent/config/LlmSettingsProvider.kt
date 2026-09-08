package com.example.llmagent.config

import com.example.llmagent.agent.ToolRegistry
import org.springframework.stereotype.Component

/**
 * Единый источник «применённых настроек LLM»:
 * - для события `agent_started` (AgentImpl);
 * - для `GET /api/llm-settings` (показ настроек в UI сразу при открытии страницы).
 *
 * apiKey и baseUrl наружу не отдаём.
 */
@Component
class LlmSettingsProvider(
    private val llm: LlmProperties,
    private val agent: AgentProperties,
    private val toolRegistry: ToolRegistry,
) {

    fun settings(): Map<String, Any?> = mapOf(
        "provider" to llm.provider,
        "model" to llm.model,
        "temperature" to llm.temperature,
        "topP" to llm.topP,
        // 0/не задано нормализуем в null — «параметр не применяется»
        "topK" to llm.topK?.takeIf { it > 0 },
        "maxTokens" to llm.maxTokens?.takeIf { it > 0 },
        "timeoutSeconds" to llm.timeoutSeconds,
        "maxToolCallIterations" to agent.maxToolCallIterations,
        "tools" to toolRegistry.names().sorted(),
    )
}
