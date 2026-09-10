package com.example.llmagent.config

import com.example.llmagent.agent.Agent
import com.example.llmagent.agent.AgentImpl
import com.example.llmagent.agent.GpuStackLlmClient
import com.example.llmagent.agent.LlmClient
import com.example.llmagent.agent.MockLlmClient
import com.example.llmagent.agent.SessionStore
import com.example.llmagent.agent.ToolRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfig {

    /**
     * Выбор транспорта к LLM по LLM_PROVIDER (mock — по умолчанию, для локального запуска без ключа).
     * provider/baseUrl/apiKey — статические (из env на старте); per-request параметры
     * (model, temperature, top_p, top_k, max_tokens, timeout) клиент берёт из динамических
     * настроек [LlmSettings] на каждый запрос.
     */
    @Bean
    fun llmClient(props: LlmProperties, om: ObjectMapper, settings: LlmSettings): LlmClient =
        when (props.provider.lowercase()) {
            "mock" -> MockLlmClient()
            "gpustack" -> GpuStackLlmClient(props, om, settings)
            else -> throw IllegalArgumentException(
                "Неизвестный LLM_PROVIDER='${props.provider}' (ожидается mock|gpustack)"
            )
        }

    @Bean
    fun agent(
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        sessionStore: SessionStore,
        agentProperties: AgentProperties,
        settings: LlmSettings,
        settingsProvider: LlmSettingsProvider,
        om: ObjectMapper,
    ): Agent = AgentImpl(llmClient, toolRegistry, sessionStore, agentProperties, settingsProvider, settings, om)
}
