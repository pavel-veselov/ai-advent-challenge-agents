package com.example.llmagent.agent.tools

import com.example.llmagent.agent.AgentSchedulerStore
import com.example.llmagent.agent.Tool
import com.example.llmagent.agent.ToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.stereotype.Component

/**
 * Инструмент агента `agent_scheduler_create` (Day-17/18): создаёт периодическую задачу —
 * агент будет периодически запускаться с промптом [AgentSchedulerStore.create].
 * Аргументы (camelCase, как у остальных инструментов): name, intervalSeconds, prompt.
 */
@Component
class AgentSchedulerCreateTool(
    private val store: AgentSchedulerStore,
    private val om: ObjectMapper,
) : Tool {

    override val name = "agent_scheduler_create"

    override val description =
        "Создаёт периодическую задачу: агент будет запускаться с заданным промптом каждые interval_seconds. " +
            "Аргументы JSON: {\"name\": \"имя задачи\" (обязательно), \"intervalSeconds\": целые секунды " +
            "(обязательно, > 0), \"prompt\": \"промпт для агента\" (обязательно)}. " +
            "Возвращает созданную задачу (id, интервал, промпт, enabled)."

    override val parameters: JsonNode = run {
        val root = JsonNodeFactory.instance.objectNode()
        root.put("type", "object")
        val props = root.putObject("properties")
        val name = props.putObject("name")
        name.put("type", "string")
        name.put("description", "Имя задачи")
        val interval = props.putObject("intervalSeconds")
        interval.put("type", "integer")
        interval.put("description", "Интервал запуска в секундах (целое > 0)")
        val prompt = props.putObject("prompt")
        prompt.put("type", "string")
        prompt.put("description", "Промпт, с которым агент запускается по расписанию")
        root.putArray("required").add("name").add("intervalSeconds").add("prompt")
        root
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val name = (args["name"] as? String)?.trim().orEmpty()
        val prompt = (args["prompt"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return ToolResult("Аргумент 'name' (string) обязателен", true)
        if (prompt.isEmpty()) return ToolResult("Аргумент 'prompt' (string) обязателен", true)
        val interval = toLong(args["intervalSeconds"])
            ?: return ToolResult("Аргумент 'intervalSeconds' (целое > 0) обязателен", true)
        val job = store.create(name, interval, prompt)
            ?: return ToolResult("Не удалось создать задачу (сбой БД)", true)
        return ToolResult(om.writeValueAsString(job), false)
    }

    private fun toLong(value: Any?): Long? = when (value) {
        null -> null
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
