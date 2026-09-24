package com.example.llmagent.agent.tools

import com.example.llmagent.agent.AgentSchedulerStore
import com.example.llmagent.agent.Tool
import com.example.llmagent.agent.ToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.stereotype.Component

/**
 * Инструмент агента `agent_scheduler_update` (Day-17/18): частичное обновление задачи
 * планировщика — интервал, промпт, имя или флаг включения (переданные поля перезаписываются).
 * Аргументы (camelCase): id, intervalSeconds?, prompt?, name?, enabled?.
 */
@Component
class AgentSchedulerUpdateTool(
    private val store: AgentSchedulerStore,
    private val om: ObjectMapper,
) : Tool {

    override val name = "agent_scheduler_update"

    override val description =
        "Обновляет периодическую задачу планировщика по id. Аргументы JSON: " +
            "{\"id\": целое (обязательно), \"intervalSeconds\": целые секунды (опционально), " +
            "\"prompt\": текст (опционально), \"name\": текст (опционально), \"enabled\": boolean (опционально)}. " +
            "Переданные поля перезаписываются, остальные сохраняются."

    override val parameters: JsonNode = run {
        val root = JsonNodeFactory.instance.objectNode()
        root.put("type", "object")
        val props = root.putObject("properties")
        val id = props.putObject("id")
        id.put("type", "integer")
        id.put("description", "Id задачи")
        val interval = props.putObject("intervalSeconds")
        interval.put("type", "integer")
        interval.put("description", "Новый интервал запуска в секундах")
        val prompt = props.putObject("prompt")
        prompt.put("type", "string")
        prompt.put("description", "Новый промпт")
        val name = props.putObject("name")
        name.put("type", "string")
        name.put("description", "Новое имя задачи")
        val enabled = props.putObject("enabled")
        enabled.put("type", "boolean")
        enabled.put("description", "Включить/выключить задачу")
        root.putArray("required").add("id")
        root
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val id = toLong(args["id"])
            ?: return ToolResult("Аргумент 'id' (целое) обязателен", true)
        val interval = toLong(args["intervalSeconds"])
        val prompt = (args["prompt"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val name = (args["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val enabled = (args["enabled"] as? Boolean)
        val job = store.update(id, name, interval, prompt, enabled)
            ?: return ToolResult("Задача $id не найдена", true)
        return ToolResult(om.writeValueAsString(job), false)
    }

    private fun toLong(value: Any?): Long? = when (value) {
        null -> null
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
