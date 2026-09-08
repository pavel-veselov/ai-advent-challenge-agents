package com.example.llmagent.agent.tools

import com.example.llmagent.agent.Tool
import com.example.llmagent.agent.ToolResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Текущие дата/время (UTC, ISO-8601). */
@Component
class GetCurrentDateTimeTool : Tool {

    override val name = "get_current_datetime"
    override val description = "Возвращает текущие дату и время в UTC (ISO-8601). Аргументов не требует."

    override val parameters: JsonNode = run {
        val root = JsonNodeFactory.instance.objectNode()
        root.put("type", "object")
        root.putObject("properties")
        root
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult =
        ToolResult(Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
}
