package com.example.llmagent.config

import com.example.llmagent.agent.ToolRegistry
import org.springframework.stereotype.Component

/**
 * Единый источник «применённых настроек LLM»:
 * - для события `agent_started` (AgentImpl);
 * - для `GET /api/llm-settings` (показ настроек в UI сразу при открытии страницы).
 *
 * LLM-часть (model/temperature/.../contextLimit) приходит из [LlmSettings] — динамически
 * изменяемой ([DynamicLlmSettings], управляется через PUT /api/llm-settings); поверх неё
 * добавляются статические настройки агентского слоя (`maxToolCallIterations`) и список
 * зарегистрированных инструментов.
 *
 * apiKey и baseUrl наружу не отдаём.
 */
@Component
class LlmSettingsProvider(
    private val settings: LlmSettings,
    private val agent: AgentProperties,
    private val toolRegistry: ToolRegistry,
) {

    fun settings(): Map<String, Any?> = settings.settings() + mapOf(
        "maxToolCallIterations" to agent.maxToolCallIterations,
        "tools" to toolRegistry.names().sorted(),
    )
}
