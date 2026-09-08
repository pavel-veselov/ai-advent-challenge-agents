package com.example.llmagent.agent

import com.fasterxml.jackson.databind.JsonNode

/** Объявление инструмента в OpenAI-совместимой схеме (для поля tools). */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonNode,
)

/** Вызов инструмента, запрошенный моделью в tool_calls. */
data class LlmToolCall(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String = "{}",
)

/** Сообщение истории диалога для LLM. */
data class LlmMessage(
    val role: String, // system | user | assistant | tool
    val content: String? = null,
    val toolCalls: List<LlmToolCall>? = null,
    val toolCallId: String? = null,
)
