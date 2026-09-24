package com.example.llmagent.agent.tools

import com.example.llmagent.agent.AgentSchedulerStore
import com.example.llmagent.agent.Tool
import com.example.llmagent.agent.ToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.stereotype.Component

/**
 * Инструмент агента `agent_scheduler_list` (Day-17/18): список всех периодических задач
 * планировщика (JSON-массив). Аргументов не требует.
 */
@Component
class AgentSchedulerListTool(
    private val store: AgentSchedulerStore,
    private val om: ObjectMapper,
) : Tool {

    override val name = "agent_scheduler_list"

    override val description =
        "Возвращает JSON-массив всех периодических задач планировщика (id, имя, интервал, промпт, enabled). " +
            "Аргументов не требует."

    override val parameters: JsonNode = run {
        val root = JsonNodeFactory.instance.objectNode()
        root.put("type", "object")
        root.putObject("properties")
        root
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult =
        ToolResult(om.writeValueAsString(store.list()), false)
}
