package com.example.llmagent.config

import com.example.llmagent.agent.Agent
import com.example.llmagent.agent.AgentImpl
import com.example.llmagent.agent.GpuStackLlmClient
import com.example.llmagent.agent.LlmClient
import com.example.llmagent.agent.LongTermMemoryStore
import com.example.llmagent.agent.ProfileStore
import com.example.llmagent.agent.SessionBranchStore
import com.example.llmagent.agent.SessionCompressionStore
import com.example.llmagent.agent.SessionContextStore
import com.example.llmagent.agent.SessionFactsStore
import com.example.llmagent.agent.SessionStore
import com.example.llmagent.agent.TaskStateStore
import com.example.llmagent.agent.ToolRegistry
import com.example.llmagent.agent.WorkingMemoryStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
@Configuration
class AppConfig {

    /**
     * Единственный транспорт к LLM — реальная интеграция с GPUStack (mock-режим удалён).
     * provider/baseUrl/apiKey — статические (из env на старте); per-request параметры
     * (model, temperature, top_p, top_k, max_tokens, timeout) клиент берёт из динамических
     * настроек [LlmSettings] на каждый запрос.
     * Приложение не стартует без LLM_BASE_URL и LLM_API_KEY — fail-fast на этапе конфигурации.
     */
    @Bean
    fun llmClient(props: LlmProperties, om: ObjectMapper, settings: LlmSettings): LlmClient {
        require(props.baseUrl.isNotBlank()) {
            "LLM_BASE_URL не задан: реальная интеграция с GPUStack обязательна (env LLM_BASE_URL)."
        }
        require(props.apiKey.isNotBlank()) {
            "LLM_API_KEY не задан: реальная интеграция с GPUStack обязательна (env LLM_API_KEY)."
        }
        return GpuStackLlmClient(props, om, settings)
    }

    @Bean
    fun agent(
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        sessionStore: SessionStore,
        agentProperties: AgentProperties,
        settings: LlmSettings,
        sessionLlmSettings: SessionLlmSettingsProvider,
        compressionStore: SessionCompressionStore,
        contextStore: SessionContextStore,
        factsStore: SessionFactsStore,
        branchStore: SessionBranchStore,
        workingMemoryStore: WorkingMemoryStore,
        longTermMemoryStore: LongTermMemoryStore,
        profileStore: ProfileStore,
        appSettingsStore: AppSettingsStore,
        taskStateStore: TaskStateStore,
        workflowSettings: WorkflowSettings,
        om: ObjectMapper,
    ): Agent = AgentImpl(
        llmClient, toolRegistry, sessionStore, agentProperties, settings,
        sessionLlmSettings, compressionStore, om, contextStore, factsStore, branchStore,
        workingMemoryStore, longTermMemoryStore, profileStore, appSettingsStore, taskStateStore,
        workflowSettings,
    )
}
