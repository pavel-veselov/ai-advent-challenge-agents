package com.example.llmagent.agent

import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettingsProvider
import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux
import java.nio.file.Path
import java.time.Duration

/** LLM со скриптом ответов: каждая итерация берёт следующий элемент. */
private class ScriptedLlmClient(private val script: List<List<LlmEvent>>) : LlmClient {
    private val calls = java.util.concurrent.atomic.AtomicInteger(0)
    override fun streamChat(messages: List<LlmMessage>, tools: List<ToolDefinition>): Flux<LlmEvent> {
        val i = calls.getAndIncrement()
        if (i >= script.size) {
            throw IllegalStateException("Неожиданный дополнительный вызов LLM (#$i)")
        }
        return Flux.fromIterable(script[i])
    }
}

class AgentLoopTest {

    @TempDir
    lateinit var tmpDir: Path

    // SQLite-хранилище на временном файле — агент теперь пишет историю в БД
    private val sessionStore: SessionStore by lazy { SqliteTestSupport.store(tmpDir.resolve("agent-loop.db")) }
    private val om = ObjectMapper()
    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))

    private fun agent(llm: LlmClient, maxIterations: Int = 8): AgentImpl {
        val agentProps = AgentProperties(maxIterations)
        val settingsProvider = LlmSettingsProvider(LlmProperties(), agentProps, tools)
        return AgentImpl(llm, tools, sessionStore, agentProps, settingsProvider, om)
    }

    @Test
    fun toolCycleThenFinalAnswerEmitsFullEventSequence() {
        val llm = ScriptedLlmClient(
            listOf(
                // Итерация 1: модель просит вызвать calculator
                listOf(
                    LlmEvent.ToolCallsComplete(
                        listOf(LlmToolCall(index = 0, id = "call_1", name = "calculator", arguments = "{\"expression\": \"2+2\"}"))
                    ),
                    LlmEvent.Finished("tool_calls"),
                ),
                // Итерация 2: токены + финальный ответ
                listOf(LlmEvent.ContentDelta("4"), LlmEvent.Finished("stop")),
            )
        )

        val events = agent(llm).run("s1", "Сколько будет 2+2?").collectList().block(Duration.ofSeconds(10))!!

        assertEquals(
            listOf(
                "agent_started",
                "llm_request_started",
                "llm_response_finished",
                "tool_call_started",
                "tool_call_finished",
                "llm_request_started",
                "llm_token",
                "llm_response_finished",
                "agent_finished",
            ),
            events.map { it.type },
        )

        val toolStarted = events.filterIsInstance<ToolCallStarted>().single()
        assertEquals("calculator", toolStarted.toolName)
        assertEquals(0, toolStarted.idx)
        assertEquals(mapOf("expression" to "2+2"), toolStarted.args)

        val toolFinished = events.filterIsInstance<ToolCallFinished>().single()
        assertEquals("success", toolFinished.status)
        assertEquals("4", toolFinished.result)

        val finished = events.filterIsInstance<AgentFinished>().single()
        assertEquals("4", finished.finalText)

        // История диалога обновилась (assistant-сообщение добавлено)
        val history = sessionStore.get("s1")
        assertTrue(history.any { it.role == "user" && it.content == "Сколько будет 2+2?" })
        assertTrue(history.any { it.role == "assistant" && it.content == "4" })
    }

    @Test
    fun mockToolCallRoutedToCalculatorForTwoPlusTwo() {
        val mock = MockLlmClient()
        val events = agent(mock).run("s-math", "сколько будет 2+2?").collectList().block(Duration.ofSeconds(10))!!

        val types = events.map { it.type }
        assertTrue(types.contains("tool_call_started"))
        assertTrue(types.contains("tool_call_finished"))

        val toolStarted = events.filterIsInstance<ToolCallStarted>().single()
        assertEquals("calculator", toolStarted.toolName)
        assertEquals(mapOf("expression" to "2+2"), toolStarted.args)

        val finished = events.filterIsInstance<AgentFinished>().single()
        assertEquals("Результат: 4", finished.finalText)
    }

    @Test
    fun iterationLimitSurfacesClearError() {
        val alwaysTool = object : LlmClient {
            override fun streamChat(messages: List<LlmMessage>, tools: List<ToolDefinition>): Flux<LlmEvent> =
                Flux.just(
                    LlmEvent.ToolCallsComplete(
                        listOf(LlmToolCall(index = 0, id = "c", name = "calculator", arguments = "{\"expression\": \"1+1\"}"))
                    ),
                    LlmEvent.Finished("tool_calls"),
                )
        }

        val events = agent(alwaysTool, maxIterations = 2)
            .run("s-limit", "привет")
            .collectList().block(Duration.ofSeconds(10))!!

        val errors = events.filterIsInstance<ErrorEvent>()
        assertTrue(errors.isNotEmpty(), "ожидалось error-событие при превышении лимита")
        assertTrue(errors.first().message.contains("2"), "сообщение должно упоминать лимит: ${errors.first().message}")
        assertTrue(events.none { it is AgentFinished }, "при превышении лимита не должно быть финального ответа")
    }

    @Test
    fun llmFailureProducesErrorInsteadOfFinalAnswer() {
        var requested = 0
        val broken = object : LlmClient {
            override fun streamChat(messages: List<LlmMessage>, tools: List<ToolDefinition>): Flux<LlmEvent> {
                requested++
                return Flux.error(RuntimeException("LLM сервис недоступен"))
            }
        }

        val events = agent(broken).run("s-broken", "тест").collectList().block(Duration.ofSeconds(10))!!

        assertEquals(1, requested)
        val errors = events.filterIsInstance<ErrorEvent>()
        assertTrue(errors.isNotEmpty(), "ожидалось error-событие")
        assertTrue(errors.first().message.contains("LLM сервис недоступен"))
        assertTrue(events.none { it is AgentFinished })
    }

    @Test
    fun unknownToolProducesToolErrorAndContinues() {
        val llm = ScriptedLlmClient(
            listOf(
                listOf(
                    LlmEvent.ToolCallsComplete(
                        listOf(LlmToolCall(index = 0, id = "c2", name = "no_such_tool", arguments = "{}"))
                    ),
                    LlmEvent.Finished("tool_calls"),
                ),
                listOf(LlmEvent.ContentDelta("иду дальше"), LlmEvent.Finished("stop")),
            )
        )

        val events = agent(llm).run("s-unknown", "проверка").collectList().block(Duration.ofSeconds(10))!!

        val toolFinished = events.filterIsInstance<ToolCallFinished>().single()
        assertEquals("error", toolFinished.status)
        assertTrue(toolFinished.result.contains("no_such_tool"))

        val finished = events.filterIsInstance<AgentFinished>().single()
        assertEquals("иду дальше", finished.finalText)
    }

    @Test
    fun getCurrentDateTimeToolExecutedOnDatetimeQuestion() {
        val mock = MockLlmClient()
        val events = agent(mock).run("s-date", "какое сейчас время?").collectList().block(Duration.ofSeconds(10))!!

        val toolStarted = events.filterIsInstance<ToolCallStarted>().single()
        assertEquals("get_current_datetime", toolStarted.toolName)

        val toolFinished = events.filterIsInstance<ToolCallFinished>().single()
        assertEquals("success", toolFinished.status)
        // результат — парсящееся ISO-время
        assertFalse(toolFinished.result.isBlank())
    }
}
