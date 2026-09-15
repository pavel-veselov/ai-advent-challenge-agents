package com.example.llmagent.agent

import com.example.llmagent.config.LlmSettings
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import reactor.core.publisher.Flux

/**
 * Фейковый LLM для юнит-тестов tool-цикла (например, инструмента calculator).
 *
 * Эмулирует модель, которая РЕШАЕТ ВЫЗВАТЬ ИНСТРУМЕНТ: выдаёт скриптованную
 * последовательность ответов — каждый вызов [streamChat] возвращает следующий
 * план из очереди (конструктор/[push]); когда очередь пуста — [fallback].
 * Плюс маркерный режим: если последнее сообщение промпта ДОСЛОВНО равно
 * зарегистрированному маркеру ([mark]), возвращается план маркера без
 * съема из очереди (тот же конвент, что [AgentImpl.FACTS_REQUEST_PROMPT]).
 *
 * Типовой сценарий tool-цикла: первый вызов — [MockPlan.ToolCall] (tool_call к
 * какому-нибудь инструменту), второй (после tool-результата) — [MockPlan.Text]
 * с финальным ответом.
 */
class FakeToolCallLlmClient(
    plans: List<MockPlan> = emptyList(),
    private val fallback: MockPlan = MockPlan.Text("(fake: сценарий не задан)"),
) : LlmClient {

    private val queue = ConcurrentLinkedQueue(plans)
    private val markerPlans = java.util.Collections.synchronizedMap(mutableMapOf<String, MockPlan>())
    private val callSeq = AtomicLong(0)

    /** Сколько раз клиент был вызван. */
    val callCount = AtomicInteger(0)
    /** Промпты всех вызовов, в порядке вызовов (для ассертов). */
    val prompts = java.util.Collections.synchronizedList(mutableListOf<List<LlmMessage>>())

    /** Добавляет следующий ответ в скриптованную очередь. */
    fun push(plan: MockPlan): FakeToolCallLlmClient = apply { queue.add(plan) }

    /** Привязывает ответ к маркеру (дословное совпадение с последним сообщением промпта). */
    fun mark(marker: String, plan: MockPlan): FakeToolCallLlmClient = apply { markerPlans[marker] = plan }

    /** Добавляет в очередь вызов инструмента с аргументами (JSON-строка). */
    fun toolCall(toolName: String, arguments: String): FakeToolCallLlmClient = apply {
        queue.add(MockPlan.ToolCall(toolName, arguments))
    }

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> {
        callCount.incrementAndGet()
        prompts.add(messages)
        val call = callSeq.incrementAndGet()
        val plan = messages.lastOrNull()?.content?.let { markerPlans[it] } ?: queue.poll() ?: fallback
        return Flux.fromIterable(events(plan, call))
    }

    /** События в том же виде, в каком их разбирает [AgentImpl]:
     * tool_calls → [LlmEvent.ToolCallsComplete] + Finished("tool_calls");
     * текст → ContentDelta + Finished("stop"). */
    private fun events(plan: MockPlan, call: Long): List<LlmEvent> = when (plan) {
        is MockPlan.Text -> listOf(LlmEvent.ContentDelta(plan.text), LlmEvent.Finished("stop"))
        is MockPlan.ToolCall -> listOf(
            LlmEvent.ToolCallsComplete(
                listOf(
                    LlmToolCall(
                        index = 0,
                        id = "call_fake_$call",
                        name = plan.toolName,
                        arguments = plan.arguments,
                    )
                )
            ),
            LlmEvent.Finished("tool_calls"),
        )
    }
}
