package com.example.llmagent.agent

import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.JdbcSessionLlmSettingsStore
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.example.llmagent.config.StoredSessionLlmSettings
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux

/** Фиксирует применённые настройки каждого вызова LLM (per-session в чат-потоке). */
private class CapturingLlmClient : LlmClient {
    val seen = mutableListOf<LlmSettings>()

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> {
        seen += settings
        return Flux.just(LlmEvent.ContentDelta("ок"), LlmEvent.Finished("stop"))
    }
}

/**
 * Юнит-тест разрешения per-session настроек в чат-потоке (AgentImpl): сессия со своими
 * настройками передаёт их клиенту на каждый запрос, сессия без строки — глобальные дефолты
 * (поведение как сегодня). Проверяется и payload события `agent_started`.
 */
class SessionLlmSettingsResolutionTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()
    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))

    @Test
    fun `session A and B run with different per-session llm settings`() {
        val dbFile = tmpDir.resolve("resolve.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        val sessionStore = SqliteTestSupport.store(dbFile)
        val llmStore = JdbcSessionLlmSettingsStore(jdbc)

        // Глобальные настройки «сегодня»: deepseek-v4-flash, температура 0.7, maxTokens 10000, reasoning включён
        val llmProps = LlmProperties(model = "deepseek-v4-flash")
        val global = LlmSettings.from(llmProps)
        val provider = SessionLlmSettingsProvider(llmStore, global)

        // Сессия A: свои настройки — qwen3.8-27b, температура 0.2, лимит 500, рассуждение выключено
        llmStore.save(StoredSessionLlmSettings("A", model = "qwen3.8-27b", temperature = "0.2", maxTokens = "500", reasoningEnabled = "false"))

        val llm = CapturingLlmClient()
        val agent = AgentImpl(llm, tools, sessionStore, AgentProperties(8), global, provider, SessionCompressionStore(jdbc), om)

        runAgent(agent, "A", "привет")
        val settingsA = llm.seen.last()
        assertEquals("qwen3.8-27b", settingsA.model())
        assertEquals(0.2, settingsA.temperature(), 1e-9)
        assertEquals(500, settingsA.maxTokens())
        assertEquals(false, settingsA.reasoningEnabled())
        assertEquals(198 * 1024, settingsA.contextLimit(), "контекст выведен из qwen3.8-27b по каталогу")

        // Сессия B без строки — глобальные дефолты (как сегодня)
        runAgent(agent, "B", "привет")
        val settingsB = llm.seen.last()
        assertEquals("deepseek-v4-flash", settingsB.model())
        assertEquals(0.7, settingsB.temperature(), 1e-9)
        assertEquals(10000, settingsB.maxTokens())
        assertEquals(true, settingsB.reasoningEnabled())
        assertEquals(1024 * 1024, settingsB.contextLimit(), "контекст выведен из deepseek-v4-flash по каталогу")

        // Сессия A снова — настройки не «протекли» из B обратно
        runAgent(agent, "A", "ещё")
        val settingsA2 = llm.seen.last()
        assertEquals("qwen3.8-27b", settingsA2.model())
        assertEquals(0.2, settingsA2.temperature(), 1e-9)
        assertEquals(500, settingsA2.maxTokens())
        assertEquals(false, settingsA2.reasoningEnabled())
    }

    @Test
    fun `request is built with session temperature and model via fake client`() {
        val dbFile = tmpDir.resolve("temp.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        val sessionStore = SqliteTestSupport.store(dbFile)
        val llmStore = JdbcSessionLlmSettingsStore(jdbc)

        val global = LlmSettings.from(LlmProperties(model = "deepseek-v4-flash"))
        val provider = SessionLlmSettingsProvider(llmStore, global)
        llmStore.save(
            StoredSessionLlmSettings(
                "A",
                model = "qwen3.8-27b",
                temperature = "0.15",
                topP = "0.8",
                topK = "24",
            )
        )

        val llm = CapturingLlmClient()
        val agent = AgentImpl(llm, tools, sessionStore, AgentProperties(8), global, provider, SessionCompressionStore(jdbc), om)

        runAgent(agent, "A", "привет")
        val seen = llm.seen.last()
        // клиенту (билдеру запроса) пришёл эффективный набор сессии: свой model + temperature/topP/topK
        assertEquals("qwen3.8-27b", seen.model())
        assertEquals(0.15, seen.temperature(), 1e-9)
        assertEquals(0.8, seen.topP(), 1e-9)
        assertEquals(24, seen.topK())
    }

    @Test
    fun `agent_started carries per-session settings in the same shape as global`() {
        val dbFile = tmpDir.resolve("started.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        val sessionStore = SqliteTestSupport.store(dbFile)
        val llmStore = JdbcSessionLlmSettingsStore(jdbc)

        val llmProps = LlmProperties(model = "deepseek-v4-flash")
        val global = LlmSettings.from(llmProps)
        val provider = SessionLlmSettingsProvider(llmStore, global)
        llmStore.save(StoredSessionLlmSettings("A", model = "qwen3.8-27b", maxTokens = "333", reasoningEnabled = "false"))

        val agent = AgentImpl(CapturingLlmClient(), tools, sessionStore, AgentProperties(8), global, provider, SessionCompressionStore(jdbc), om)

        val events = runAgent(agent, "A", "привет")
        val started = events.filterIsInstance<AgentStarted>().single().settings
        assertEquals("qwen3.8-27b", started["model"])
        assertEquals(333, started["maxTokens"])
        assertEquals(false, started["reasoningEnabled"])
        assertEquals(198 * 1024, started["contextLimit"])
        assertEquals("gpustack", started["provider"], "provider остаётся из глобального источника")
        assertTrue(started.containsKey("maxToolCallIterations"), "статичные поля остаются в settings")
        assertTrue(started.containsKey("tools"), "статичные поля остаются в settings")
    }

    private fun runAgent(agent: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        agent.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!
}
