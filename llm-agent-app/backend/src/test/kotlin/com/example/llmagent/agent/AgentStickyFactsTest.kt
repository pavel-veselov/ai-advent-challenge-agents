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

/**
 * LLM для sticky_facts: вызов извлечения фактов различается по последнему сообщению
 * промпта (маркер [AgentImpl.FACTS_REQUEST_PROMPT]) и возвращает [factsJson] дословно
 * (или [factsError] при сбое); вызовы основного цикла отвечают фиксированным текстом.
 */
private class FactsLlm(
    private val factsJson: String = "{}",
    private val factsError: Throwable? = null,
) : LlmClient {
    val factsCalls = AtomicInteger(0)
    val mainCalls = AtomicInteger(0)
    var lastMainPrompt: List<LlmMessage>? = null

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> = if (messages.lastOrNull()?.content == AgentImpl.FACTS_REQUEST_PROMPT) {
        factsCalls.incrementAndGet()
        factsError?.let { return@streamChat Flux.error(it) }
        Flux.just(LlmEvent.ContentDelta(factsJson), LlmEvent.Finished("stop"))
    } else {
        mainCalls.incrementAndGet()
        lastMainPrompt = messages
        Flux.just(LlmEvent.ContentDelta("ответ агента"), LlmEvent.Finished("stop"))
    }
}

private data class StickyFactsStores(
    val session: SessionStore,
    val compression: SessionCompressionStore,
    val context: SessionContextStore,
    val facts: SessionFactsStore,
    val branches: SessionBranchStore,
)

/** Юнит-тесты стратегии sticky_facts: извлечение фактов, подмешивание в контекст, fail-open. */
class AgentStickyFactsTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()

    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))

    private fun stores(dbHint: String): StickyFactsStores {
        val dbFile = tmpDir.resolve("$dbHint-${UUID.randomUUID()}.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        SqliteTestSupport.createChatMessagesTable(jdbc)
        val branches = SessionBranchStore(jdbc)
        return StickyFactsStores(
            session = SessionStore(jdbc, branchStore = branches),
            compression = SessionCompressionStore(jdbc),
            context = SessionContextStore(jdbc),
            facts = SessionFactsStore(jdbc),
            branches = branches,
        )
    }

    private fun agent(llm: LlmClient, s: StickyFactsStores): AgentImpl {
        val llmProps = LlmProperties()
        val agentProps = AgentProperties(8)
        val sessionLlmSettings = SessionLlmSettingsProvider(
            JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("stf-llm-${UUID.randomUUID()}.db"))),
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
    fun `sticky facts extracted injected into context and persisted`() {
        val s = stores("stf")
        s.context.update("s1", mapOf("strategy" to "sticky_facts", "windowSize" to 4))
        s.session.append("s1", "user", "u1")
        s.session.append("s1", "assistant", "a1")
        s.session.append("s1", "user", "u2")
        s.session.append("s1", "assistant", "a2")

        val llm = FactsLlm(factsJson = om.writeValueAsString(mapOf("имя" to "Аня", "цель" to "тест")))
        val events = run(agent(llm, s), "s1", "вопрос")

        // вызов извлечения был (маркер — запрос фактов последним сообщением)
        assertEquals(1, llm.factsCalls.get())
        assertEquals(1, llm.mainCalls.get())

        // событие facts_updated с извлечённым набором
        val fu = events.filterIsInstance<FactsUpdated>().single()
        assertEquals("facts", fu.stepId)
        assertEquals(mapOf("имя" to "Аня", "цель" to "тест"), fu.facts)

        // факты персистены
        assertEquals(mapOf("имя" to "Аня", "цель" to "тест"), s.facts.getAll("s1"))

        // контекст: system + «Известные факты» + окно (последние 4 не-системных со включённым вопросом)
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals("system", prompt[0]["role"])
        assertEquals("Известные факты:\n- имя: Аня\n- цель: тест", prompt[1]["content"])
        assertEquals(listOf("a1", "u2", "a2", "вопрос"), prompt.drop(2).map { it["content"] })

        assertEquals("sticky_facts", events.filterIsInstance<AgentStarted>().single().settings["contextStrategy"])
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `facts extraction failure fails open keeps previous facts and run continues`() {
        val s = stores("stf-fail")
        s.context.update("s1", mapOf("strategy" to "sticky_facts", "windowSize" to 10))
        // предыдущие факты уже сохранены
        s.facts.replaceAll("s1", linkedMapOf("старое" to "значение", "второе" to "сохранено"))
        s.session.append("s1", "user", "u1")
        s.session.append("s1", "assistant", "a1")

        val llm = FactsLlm(factsError = LlmApiException(500, "boom"))
        val events = run(agent(llm, s), "s1", "вопрос")

        assertEquals(1, llm.factsCalls.get())
        // error-событие в стиле контракта и ПРОДОЛЖЕНИЕ run до нормального ответа
        val errors = events.filterIsInstance<ErrorEvent>()
        assertTrue(errors.any { it.message.startsWith("Не удалось обновить факты:") && it.message.contains("Продолжаю с предыдущими фактами") })
        assertTrue(events.any { it is AgentFinished })
        assertTrue(events.none { it is FactsUpdated }, "facts_updated не должно быть при сбое")

        // прежние факты НЕ тронуты и подмешаны в контекст
        assertEquals(mapOf("старое" to "значение", "второе" to "сохранено"), s.facts.getAll("s1"))
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertTrue(
            prompt.any { it["content"]?.startsWith("Известные факты:\n- старое: значение") == true },
            "в контексте — предыдущие факты: ${prompt.map { it["content"] }}",
        )
    }

    @Test
    fun `invalid facts json fails open without touching previous facts`() {
        val s = stores("stf-badjson")
        s.context.update("s1", mapOf("strategy" to "sticky_facts"))
        s.facts.replaceAll("s1", linkedMapOf("keep" to "me"))
        s.session.append("s1", "user", "u1")

        val llm = FactsLlm(factsJson = "это не JSON")
        val events = run(agent(llm, s), "s1", "вопрос")

        assertTrue(events.any { it is ErrorEvent && it.message.contains("Не удалось обновить факты") })
        assertTrue(events.any { it is AgentFinished }, "run должен продолжиться")
        assertEquals(mapOf("keep" to "me"), s.facts.getAll("s1"))
    }

    @Test
    fun `empty facts object clears facts and omits facts message from context`() {
        val s = stores("stf-empty")
        s.context.update("s1", mapOf("strategy" to "sticky_facts"))
        s.session.append("s1", "user", "u1")

        val llm = FactsLlm(factsJson = "{}")
        val events = run(agent(llm, s), "s1", "вопрос")

        val fu = events.filterIsInstance<FactsUpdated>().single()
        assertTrue(fu.facts.isEmpty())
        assertTrue(s.facts.getAll("s1").isEmpty())
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertTrue(prompt.none { it["content"]?.startsWith("Известные факты:") == true }, "пустые факты — нет системного сообщения о них")
    }

    @Test
    fun `no facts extraction when strategy is not sticky_facts`() {
        val s = stores("stf-none")
        s.context.update("s1", mapOf("strategy" to "none"))
        s.session.append("s1", "user", "u1")

        val llm = FactsLlm(factsJson = om.writeValueAsString(mapOf("x" to "y")))
        val events = run(agent(llm, s), "s1", "вопрос")

        assertEquals(0, llm.factsCalls.get(), "извлечение фактов не вызывается вне sticky_facts")
        assertTrue(events.none { it is FactsUpdated })
        assertTrue(s.facts.getAll("s1").isEmpty())
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertTrue(prompt.none { it["content"]?.startsWith("Известные факты:") == true })
    }
}
