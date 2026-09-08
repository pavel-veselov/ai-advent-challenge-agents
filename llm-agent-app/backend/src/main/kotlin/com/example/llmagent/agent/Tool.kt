package com.example.llmagent.agent

import com.fasterxml.jackson.databind.JsonNode

data class ToolResult(val result: String, val isError: Boolean = false)

/** Инструмент агента: объявление (OpenAI-схема) + безопасное исполнение. */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonNode
    suspend fun execute(args: Map<String, Any?>): ToolResult
}
