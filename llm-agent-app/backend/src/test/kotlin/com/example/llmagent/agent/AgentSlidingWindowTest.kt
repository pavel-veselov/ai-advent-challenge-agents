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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux

/** LLM, который всегда отвечает фиксированным текстом и запоминает последний промпт main-вызова. */
private class RecordLlm(private val text: String = "Ответ") : LlmClient {
    val mainCalls = AtomicInteger(0)
    var lastMainPrompt: List<LlmMessage>? = null

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> {
        mainCalls.incrementAndGet()
        lastMainPrompt = messages
        return Flux.just(LlmEvent.ContentDelta(text), LlmEvent.Finished("stop"))
    }
}

/** Все хранилища поверх ОДНОГО временного SQLite-файла (ветки подключены к SessionStore). */
private data class SlidingWindowStores(
    val session: SessionStore,
    val compression: SessionCompressionStore,
    val context: SessionContextStore,
    val facts: SessionFactsStore,
    val branches: SessionBranchStore,
)

/** Юнит-тесты стратегии sliding_window: окно последних сообщений в контексте LLM-запроса. */
class AgentSlidingWindowTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()
    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))

    private fun stores(dbHint: String): SlidingWindowStores {
        val dbFile = tmpDir.resolve("$dbHint-${UUID.randomUUID()}.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        SqliteTestSupport.createChatMessagesTable(jdbc)
        val branches = SessionBranchStore(jdbc)
        return SlidingWindowStores(
            session = SessionStore(jdbc, branchStore = branches),
            compression = SessionCompressionStore(jdbc),
            context = SessionContextStore(jdbc),
            facts = SessionFactsStore(jdbc),
            branches = branches,
        )
    }

    private fun agent(llm: LlmClient, s: SlidingWindowStores): AgentImpl {
        val llmProps = LlmProperties()
        val agentProps = AgentProperties(8)
        val sessionLlmSettings = SessionLlmSettingsProvider(
            JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("sw-llm-${UUID.randomUUID()}.db"))),
            LlmSettings.from(llmProps),
        )
        return AgentImpl(
            llm, tools, s.session, agentProps, LlmSettings.from(llmProps), sessionLlmSettings,
            s.compression, om, s.context, s.facts, s.branches,
        )
    }

    private fun run(a: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        a.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!

    @Test
    fun `sliding window limits context to system plus last windowSize messages`() {
        val s = stores("sw")
        s.context.update("s1", mapOf("strategy" to "sliding_window", "windowSize" to 2))
        s.session.append("s1", "user", "u1")
        s.session.append("s1", "assistant", "a1")
        s.session.append("s1", "user", "u2")
        s.session.append("s1", "assistant", "a2")
        s.session.append("s1", "user", "u3")

        val llm = RecordLlm()
        val events = run(agent(llm, s), "s1", "вопрос")

        assertEquals(1, llm.mainCalls.get())
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        // system + последние 2 не-системных: [u3, вопрос]; более старые — вне контекста
        assertEquals(listOf("system", "user", "user"), prompt.map { it["role"] })
        assertEquals(listOf("u3", "вопрос"), prompt.drop(1).map { it["content"] })
        assertTrue(prompt.none { it["content"] in listOf("u1", "a1", "u2", "a2") })

        // agent_started сообщает разрешённую стратегию этого run
        assertEquals(
            "sliding_window",
            events.filterIsInstance<AgentStarted>().single().settings["contextStrategy"],
        )
        assertTrue(events.any { it is AgentFinished })
        // стратегия персистентна
        assertEquals("sliding_window", s.context.get("s1").strategy)
    }

    @Test
    fun `sliding window with window size covering history acts like full history`() {
        val s = stores("sw-full")
        s.context.update("s1", mapOf("strategy" to "sliding_window", "windowSize" to 10))
        s.session.append("s1", "user", "u1")
        s.session.append("s1", "assistant", "a1")
        s.session.append("s1", "user", "u2")

        val llm = RecordLlm()
        val events = run(agent(llm, s), "s1", "вопрос")
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("u1", "a1", "u2", "вопрос"), prompt.drop(1).map { it["content"] })
    }

    @Test
    fun `window of one keeps only the question`() {
        val s = stores("sw-1")
        s.context.update("s1", mapOf("strategy" to "sliding_window", "windowSize" to 1))
        s.session.append("s1", "user", "u1")
        s.session.append("s1", "assistant", "a1")

        val llm = RecordLlm()
        val events = run(agent(llm, s), "s1", "вопрос")
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("вопрос"), prompt.drop(1).map { it["content"] })
    }
}
