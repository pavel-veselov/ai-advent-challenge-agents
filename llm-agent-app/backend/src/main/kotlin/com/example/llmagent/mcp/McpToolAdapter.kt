package com.example.llmagent.mcp

import com.example.llmagent.agent.Tool
import com.example.llmagent.agent.ToolResult
import com.fasterxml.jackson.databind.JsonNode

/**
 * Адаптер инструмента MCP-сервера под [Tool] агента: имя инструмента — СЫРОЕ MCP-имя
 * (например `get_weather`), чтобы LLM видела чистые имена. Передача аргументов и чтение
 * результата делегируются [McpStreamableClient.callTool].
 *
 * Примечание (принятое упрощение): при коллизии имён между разными MCP-серверами
 * «побеждает» последний активированный сервер — имя в реестре одно (оно же уходит в
 * объявление tools для LLM), поэтому модель всегда видит последнее зарегистрированное.
 */
class McpToolAdapter(
    private val mcpName: String,
    override val description: String,
    override val parameters: JsonNode,
    private val client: McpStreamableClient,
) : Tool {

    override val name: String get() = mcpName

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val r = client.callTool(mcpName, args)
        return ToolResult(r.text, r.isError)
    }
}
