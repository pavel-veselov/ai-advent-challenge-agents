package com.example.llmagent.agent.tools

import com.example.llmagent.agent.AgentSchedulerStore
import com.example.llmagent.agent.Tool
import com.example.llmagent.agent.ToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.stereotype.Component

/**
 * Инструмент агента `agent_scheduler_delete` (Day-17/18): удаляет задачу планировщика по id.
 * Аргумент (camelCase): id. Успех — {"ok":true}; нет задачи — ошибка.
 */
@Component
class AgentSchedulerDeleteTool(
    private val store: AgentSchedulerStore,
    private val om: ObjectMapper,
) : Tool {

    override val name = "agent_scheduler_delete"

    override val description =
        "Удаляет периодическую задачу планировщика по id. Аргумент JSON: {\"id\": целое (обязательно)}. " +
            "Возвращает {\"ok\":true} при успехе."

    override val parameters: JsonNode = run {
        val root = JsonNodeFactory.instance.objectNode()
        root.put("type", "object")
        val props = root.putObject("properties")
        val id = props.putObject("id")
        id.put("type", "integer")
        id.put("description", "Id задачи для удаления")
        root.putArray("required").add("id")
        root
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val id = toLong(args["id"])
            ?: return ToolResult("Аргумент 'id' (целое) обязателен", true)
        if (!store.delete(id)) return ToolResult("Задача $id не найдена", true)
        val ok = om.createObjectNode().put("ok", true)
        return ToolResult(om.writeValueAsString(ok), false)
    }

    private fun toLong(value: Any?): Long? = when (value) {
        null -> null
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
