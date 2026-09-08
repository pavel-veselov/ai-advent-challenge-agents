package com.example.llmagent.agent

import com.fasterxml.jackson.databind.ObjectMapper
import reactor.core.publisher.Flux

/**
 * Детерминированный фейковый LLM для локального запуска и тестов (LLM_PROVIDER=mock).
 * - На сообщение с арифметикой отвечает запросом tool call к calculator;
 * - на вопрос о дате/времени — tool call к get_current_datetime;
 * - на tool-результат — текстовым ответом "Результат: ...";
 * - иначе — шаблонный текст.
 */
class MockLlmClient(
    private val chunkSize: Int = CHUNK_SIZE,
    private val planner: MockPlanner? = null,
) : LlmClient {

    override fun streamChat(messages: List<LlmMessage>, tools: List<ToolDefinition>): Flux<LlmEvent> =
        Flux.defer {
            val plan = planner?.plan(messages, tools) ?: defaultPlan(messages)
            val events: List<LlmEvent> = when (plan) {
                is MockPlan.Text -> plan.text.chunked(chunkSize)
                    .map { LlmEvent.ContentDelta(it) as LlmEvent } +
                    listOf(LlmEvent.Finished("stop"))

                is MockPlan.ToolCall -> listOf(
                    LlmEvent.ToolCallsComplete(
                        listOf(
                            LlmToolCall(
                                index = 0,
                                id = "call_mock_0",
                                name = plan.toolName,
                                arguments = plan.arguments,
                            )
                        )
                    ),
                    LlmEvent.Finished("tool_calls"),
                )
            }
            Flux.fromIterable(events)
        }

    /** Определяет сценарий по последнему сообщению истории. */
    internal fun defaultPlan(messages: List<LlmMessage>): MockPlan {
        val lastTool = messages.lastOrNull { it.role == "tool" }
        if (lastTool != null) return MockPlan.Text("Результат: ${lastTool.content.orEmpty()}")
        val user = messages.lastOrNull { it.role == "user" }?.content ?: return MockPlan.Text("(пустое сообщение)")
        ARITHMETIC.find(user)?.let { return MockPlan.ToolCall("calculator", "{\"expression\":${OM.writeValueAsString(it.value)}}") }
        if (user.contains("дата", ignoreCase = true) || user.contains("время", ignoreCase = true) ||
            user.contains("date", ignoreCase = true) || user.contains("time", ignoreCase = true)
        ) {
            return MockPlan.ToolCall("get_current_datetime", "{}")
        }
        return MockPlan.Text("Я (mock) получил ваше сообщение: «$user»")
    }

    private companion object {
        const val CHUNK_SIZE = 6
        val OM = ObjectMapper()
        val ARITHMETIC = Regex("""-?\d+(?:\.\d+)?(?:\s*[+\-*/]\s*-?\d+(?:\.\d+)?)+""")
    }
}

/** Сценарий ответа mock-LLM. */
sealed interface MockPlan {
    data class Text(val text: String) : MockPlan
    data class ToolCall(val toolName: String, val arguments: String) : MockPlan
}

/** Переопределяемый планировщик сценариев для тестов. */
fun interface MockPlanner {
    fun plan(messages: List<LlmMessage>, tools: List<ToolDefinition>): MockPlan
}
