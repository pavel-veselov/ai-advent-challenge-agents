package com.example.llmagent.agent

import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.JdbcSessionLlmSettingsStore
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux

/** LLM со скриптом ответов, запоминающий (messages, tools) каждого вызова. */
private class RecordingLlmClient(private val script: List<List<LlmEvent>>) : LlmClient {
    private val calls = java.util.concurrent.atomic.AtomicInteger(0)

    /** Пары (messages, tools) всех вызовов, в порядке вызовов. */
    val requests = java.util.Collections.synchronizedList(
        mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()
    )

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> {
        val i = calls.getAndIncrement()
        if (i >= script.size) {
            throw IllegalStateException("Неожиданный дополнительный вызов LLM (#$i)")
        }
        requests += messages to tools
        return Flux.fromIterable(script[i])
    }
}

/**
 * Юнит-тесты гейта toolsEnabled и unknown-tool guard в агентском цикле (AgentImpl):
 * - toolsEnabled=false: LLM-запросы уходят без списка инструментов, системный промпт —
 *   вариант без инструментов, agent_started сообщает tools=[];
 * - unknown-tool guard: корректирующий tool-результат ошибки; после ДВУХ ответов подряд
 *   с несуществующими инструментами — эскалация (запрос без tools + системная заметка);
 *   счётчик сбрасывается ответом без несуществующих вызовов.
 */
class AgentToolsEnabledTest {

    @TempDir
    lateinit var tmpDir: Path

    private val sessionStore: SessionStore by lazy { SqliteTestSupport.store(tmpDir.resolve("agent-tools.db")) }
    private val llmSettingsStore: JdbcSessionLlmSettingsStore by lazy {
        JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("agent-tools-llm.db")))
    }
    private val om = ObjectMapper()
    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))

    private fun agent(llm: LlmClient, llmProps: LlmProperties = LlmProperties()): AgentImpl {
        val sessionLlmSettings = SessionLlmSettingsProvider(llmSettingsStore, LlmSettings.from(llmProps))
        val compressionStore = SessionCompressionStore(
            SqliteTestSupport.jdbc(tmpDir.resolve("agent-tools-compression.db"))
        )
        return AgentImpl(
            llm, tools, sessionStore, AgentProperties(8), LlmSettings.from(llmProps),
            sessionLlmSettings, compressionStore, om,
        )
    }

    private fun runAgent(agent: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        agent.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!

    private fun toolCall(name: String) = listOf(
        LlmEvent.ToolCallsComplete(
            listOf(LlmToolCall(index = 0, id = "c_$name", name = name, arguments = "{}"))
        ),
        LlmEvent.Finished("tool_calls"),
    )

    private fun text(t: String) = listOf(LlmEvent.ContentDelta(t), LlmEvent.Finished("stop"))

    @Test
    fun `toolsEnabled false sends empty tools list and no-tools system prompt`() {
        val llm = RecordingLlmClient(listOf(text("ок")))
        val events = runAgent(agent(llm, llmProps = LlmProperties(toolsEnabled = false)), "s-off", "привет")

        val (messages, toolsArg) = llm.requests.single()
        assertTrue(toolsArg.isEmpty(), "при toolsEnabled=false инструменты не передаются: $toolsArg")
        assertEquals("system", messages.first().role)
        assertEquals(AgentImpl.SYSTEM_PROMPT_WITHOUT_TOOLS, messages.first().content)

        val started = events.filterIsInstance<AgentStarted>().single().settings
        assertEquals(false, started["toolsEnabled"], "agent_started несёт toolsEnabled=false: ${started["toolsEnabled"]}")
        assertTrue((started["tools"] as List<*>).isEmpty(), "agent_started.tools должен быть пустым: ${started["tools"]}")

        assertEquals("ок", events.filterIsInstance<AgentFinished>().single().finalText)
    }

    @Test
    fun `toolsEnabled true by default keeps tools and standard system prompt`() {
        val llm = RecordingLlmClient(listOf(text("ок")))
        runAgent(agent(llm), "s-on", "привет")

        val (messages, toolsArg) = llm.requests.single()
        assertTrue(toolsArg.isNotEmpty(), "по умолчанию инструменты передаются")
        assertTrue(
            messages.first().content != AgentImpl.SYSTEM_PROMPT_WITHOUT_TOOLS,
            "обычный системный промпт, а не вариант без инструментов",
        )
    }

    @Test
    fun `unknown tool gets corrective result with available tools`() {
        val llm = RecordingLlmClient(listOf(toolCall("no_such_tool"), text("иду дальше")))
        val events = runAgent(agent(llm), "s-guard", "проверка")

        val finished = events.filterIsInstance<ToolCallFinished>().single()
        assertEquals("error", finished.status)
        assertTrue(finished.result.contains("no_such_tool"), finished.result)
        assertTrue(finished.result.contains("calculator"), "подсказка перечисляет доступные инструменты: ${finished.result}")
        assertTrue(finished.result.contains("Не выдумывай инструменты"), finished.result)
        // один удар не эскалирует: второй запрос всё ещё с инструментами
        assertTrue(llm.requests[1].second.isNotEmpty(), "после одного неизвестного вызова инструменты ещё передаются")
        assertEquals("иду дальше", events.filterIsInstance<AgentFinished>().single().finalText)
    }

    @Test
    fun `known tool in mixed response still executes and does not escalate`() {
        val llm = RecordingLlmClient(
            listOf(
                listOf(
                    LlmEvent.ToolCallsComplete(
                        listOf(
                            LlmToolCall(index = 0, id = "c_bad", name = "no_such_tool", arguments = "{}"),
                            LlmToolCall(index = 1, id = "c_ok", name = "calculator", arguments = "{\"expression\": \"2+2\"}"),
                        )
                    ),
                    LlmEvent.Finished("tool_calls"),
                ),
                text("готово"),
            )
        )
        val events = runAgent(agent(llm), "s-mixed", "проверка")

        val finishedTools = events.filterIsInstance<ToolCallFinished>()
        assertEquals(2, finishedTools.size)
        // известный инструмент исполняется как обычно, несуществующий — ошибка
        assertTrue(finishedTools.any { it.toolName == "calculator" && it.status == "success" })
        assertTrue(finishedTools.any { it.toolName == "no_such_tool" && it.status == "error" })

        assertTrue(llm.requests[1].second.isNotEmpty(), "смешанный ответ (1 удар) не эскалирует")
        assertEquals("готово", events.filterIsInstance<AgentFinished>().single().finalText)
    }

    @Test
    fun `two consecutive unknown tool responses escalate to no-tools request with note`() {
        val llm = RecordingLlmClient(listOf(toolCall("no_such_tool"), toolCall("no_such_tool"), text("отвечаю текстом")))
        val events = runAgent(agent(llm), "s-escalate", "проверка")

        assertEquals(3, llm.requests.size)
        assertTrue(llm.requests[0].second.isNotEmpty(), "1-й запрос — с инструментами")
        assertTrue(llm.requests[1].second.isNotEmpty(), "2-й запрос — ещё с инструментами (1 удар)")
        assertTrue(llm.requests[2].second.isEmpty(), "3-й запрос после двух ударов — без инструментов")

        val third = llm.requests[2].first
        assertTrue(
            third.any { it.role == "system" && it.content == AgentImpl.TOOLS_UNAVAILABLE_NOTE },
            "системная заметка об отключении инструментов: $third",
        )

        assertEquals(2, events.filterIsInstance<ToolCallFinished>().count { it.status == "error" })
        assertEquals("отвечаю текстом", events.filterIsInstance<AgentFinished>().single().finalText)
    }

    @Test
    fun `counter resets after response with only known tools`() {
        // текстовый ответ завершает run, поэтому сброс счётчика проверяем через известный
        // инструмент: ответ только с известными вызовами сбрасывает удары в 0
        val calculatorCall = listOf(
            LlmEvent.ToolCallsComplete(
                listOf(LlmToolCall(index = 0, id = "c_calc", name = "calculator", arguments = "{\"expression\": \"2+2\"}"))
            ),
            LlmEvent.Finished("tool_calls"),
        )
        val llm = RecordingLlmClient(
            listOf(toolCall("no_such_tool"), calculatorCall, toolCall("no_such_tool"), text("финал"))
        )
        runAgent(agent(llm), "s-reset", "проверка")

        assertEquals(4, llm.requests.size)
        assertTrue(llm.requests[1].second.isNotEmpty(), "2-й запрос — с инструментами (1 удар)")
        assertTrue(llm.requests[2].second.isNotEmpty(), "известный вызов сбросил счётчик: 3-й запрос снова с инструментами")
        assertTrue(llm.requests[3].second.isNotEmpty(), "4-й запрос тоже с инструментами — второго удара подряд не было")
    }
}
