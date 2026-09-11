package com.example.llmagent.agent

import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.JdbcSessionLlmSettingsStore
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * LLM, различающий вызов резюмирования (последнее сообщение — SUMMARY_REQUEST_PROMPT) и вызовы
 * основного цикла. Позволяет проверять логику триггера сжатия и сборку контекста «в юнитах».
 */
private class FakeSummaryLlm(
    private val summarize: (List<LlmMessage>) -> Flux<LlmEvent>,
    private val onMain: (List<LlmMessage>) -> Flux<LlmEvent> =
        { Flux.just(LlmEvent.ContentDelta("ответ"), LlmEvent.Finished("stop")) },
) : LlmClient {
    val summaryCalls = AtomicInteger(0)
    val mainCalls = AtomicInteger(0)
    var lastSummaryPrompt: List<LlmMessage>? = null
    var lastMainPrompt: List<LlmMessage>? = null

    override fun streamChat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        settings: LlmSettings,
    ): Flux<LlmEvent> {
        // различаем вызов резюмирования по ЗАПРОСУ СЖАТИЯ последним сообщением промпта
        if (messages.lastOrNull()?.content == AgentImpl.SUMMARY_REQUEST_PROMPT) {
            summaryCalls.incrementAndGet()
            lastSummaryPrompt = messages
            return summarize(messages)
        }
        mainCalls.incrementAndGet()
        lastMainPrompt = messages
        return onMain(messages)
    }
}

/** Юнит-тесты сжатия истории: триггер, персистентность резюме, сборка контекста, fail-open. */
class AgentCompressionTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()
    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))

    /** SessionStore + SessionCompressionStore поверх ОДНОГО временного SQLite-файла. */
    private fun stores(dbName: String): Pair<SessionStore, SessionCompressionStore> {
        val dbFile = tmpDir.resolve(dbName)
        val sessionStore = SqliteTestSupport.store(dbFile)
        val compressionStore = SessionCompressionStore(SqliteTestSupport.jdbc(dbFile))
        return sessionStore to compressionStore
    }

    private fun agent(llm: LlmClient, sessionStore: SessionStore, compressionStore: SessionCompressionStore): AgentImpl {
        val llmProps = LlmProperties()
        val agentProps = AgentProperties(8)
        val sessionLlmSettings = SessionLlmSettingsProvider(
            JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("agent-compression-llm.db"))),
            LlmSettings.from(llmProps),
        )
        return AgentImpl(llm, tools, sessionStore, agentProps, LlmSettings.from(llmProps), sessionLlmSettings, compressionStore, om)
    }

    private fun run(agent: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        agent.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!

    @Test
    fun `below threshold does not call summarization and uses full history until summary exists`() {
        val (sessionStore, compressionStore) = stores("below.db")
        compressionStore.updateSettings("s1", mapOf("enabled" to true, "keepLast" to 2, "summaryEvery" to 10))
        sessionStore.append("s1", "user", "u1")
        sessionStore.append("s1", "assistant", "a1")
        sessionStore.append("s1", "user", "u2")
        sessionStore.append("s1", "assistant", "a2")

        val llm = FakeSummaryLlm(summarize = { Flux.error(IllegalStateException("не должен вызываться")) })
        val events = run(agent(llm, sessionStore, compressionStore), "s1", "u3")

        // total=5, foldable=первые 3 сообщения < summaryEvery=10 → вызова резюмирования нет
        assertEquals(0, llm.summaryCalls.get())
        assertEquals(1, llm.mainCalls.get())
        assertTrue(events.none { it.type == "context_summary_started" })
        assertTrue(events.none { it.type == "context_summary_finished" })
        assertNull(compressionStore.getSummary("s1"))

        // резюме ещё нет → полная история (без резюме хвостовой контекст терял бы старые
        // сообщения без суммаризации): system + u1,a1,u2,a2 + вопрос
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user"), prompt.map { it["role"] })
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3"), prompt.drop(1).map { it["content"] })

        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `at threshold compresses, persists summary and includes it in context`() {
        val (sessionStore, compressionStore) = stores("at.db")
        compressionStore.updateSettings("s2", mapOf("enabled" to true, "keepLast" to 2, "summaryEvery" to 3))
        sessionStore.append("s2", "user", "u1")
        sessionStore.append("s2", "assistant", "a1")
        sessionStore.append("s2", "user", "u2")
        sessionStore.append("s2", "assistant", "a2")
        sessionStore.append("s2", "user", "u3")
        sessionStore.append("s2", "assistant", "a3")

        val llm = FakeSummaryLlm(
            summarize = { Flux.just(LlmEvent.ContentDelta("СВОДКА-1"), LlmEvent.Finished("stop", LlmUsage(10, 5))) }
        )
        val events = run(agent(llm, sessionStore, compressionStore), "s2", "u4")

        // total=7 (с вопросом u4), foldable = вне хвоста (index<size-1-keep=4) и не покрытые
        // резюме → (u1,a1,u2,a2) = 4 >= summaryEvery=3 → сжатие выполнилось; вопрос и хвост
        // (u3,a3) в резюмирование не уходят и в хвосте не дублируются
        assertEquals(1, llm.summaryCalls.get())
        assertEquals(1, llm.mainCalls.get())

        val started = events.filterIsInstance<ContextSummaryStarted>().single()
        assertEquals(4, started.foldCount)
        // в событии started — точный промпт резюмирования: сворачиваемые сообщения + запрос сжатия
        assertEquals(listOf("u1", "a1", "u2", "a2"), started.prompt.dropLast(1).map { it["content"] })
        assertEquals(AgentImpl.SUMMARY_REQUEST_PROMPT, started.prompt.last()["content"])
        assertEquals("user", started.prompt.last()["role"])
        val finished = events.filterIsInstance<ContextSummaryFinished>().single()
        assertEquals(4, finished.foldCount)
        assertEquals(10, finished.promptTokens)
        assertEquals(5, finished.completionTokens)
        // дословный текст резюме от LLM уходит в событие finished (для лога шагов)
        assertEquals("СВОДКА-1", finished.summary)
        // размер контекста до/после (токенизатор o200k): сжатие должно уменьшать размер, оба > 0
        assertTrue(finished.contextTokensBefore > finished.contextTokensAfter)
        assertTrue(finished.contextTokensAfter > 0)

        // резюме сохранено с границей по последнему свернутому сообщению (a2)
        val summary = compressionStore.getSummary("s2")!!
        assertEquals("СВОДКА-1", summary.summary)
        val stored = sessionStore.getStored("s2")
        assertEquals(stored[3].id, summary.uptoOrder)
        // заметка о сжатии — системная строка в истории (после ответа ассистента будет ещё
        // и реплика, поэтому ищем по role, а не по «последнее сообщение»)
        val notice = stored.last { it.role == "system" }
        assertEquals("system", notice.role)
        assertTrue(notice.content.contains("Сжатие контекста: 4 старых сообщений"))
        // заметка показывает размер контекста до/после сжатия — те же числа, что в событии
        assertTrue(
            notice.content.contains(
                "Контекст: ${finished.contextTokensBefore} → ${finished.contextTokensAfter} токенов",
            ),
            "notice: ${notice.content}",
        )

        // контекст основного запроса: system + «Резюме ранее» + последние 2 до вопроса + вопрос
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("system", "system", "user", "assistant", "user"), prompt.map { it["role"] })
        assertEquals("Резюме ранее: СВОДКА-1", prompt[1]["content"])
        assertEquals(listOf("u3", "a3", "u4"), prompt.drop(2).map { it["content"] })

        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `summary failure fails open to full history and run continues`() {
        val (sessionStore, compressionStore) = stores("fail.db")
        compressionStore.updateSettings("s3", mapOf("enabled" to true, "keepLast" to 2, "summaryEvery" to 3))
        sessionStore.append("s3", "user", "u1")
        sessionStore.append("s3", "assistant", "a1")
        sessionStore.append("s3", "user", "u2")
        sessionStore.append("s3", "assistant", "a2")
        sessionStore.append("s3", "user", "u3")
        sessionStore.append("s3", "assistant", "a3")

        val llm = FakeSummaryLlm(summarize = { Flux.error(LlmApiException(500, "boom")) })
        val events = run(agent(llm, sessionStore, compressionStore), "s3", "u4")

        assertEquals(1, llm.summaryCalls.get())
        // ошибка резюмирования → error-событие, run не упал, ответ есть
        val errors = events.filterIsInstance<ErrorEvent>()
        assertTrue(errors.isNotEmpty(), "ожидалось error-событие о сбое сжатия")
        assertTrue(errors.first().message.contains("сжатия"), "сообщение: ${errors.first().message}")
        assertTrue(errors.first().message.contains("boom"), "сообщение: ${errors.first().message}")
        assertTrue(events.any { it is AgentFinished }, "run должен продолжиться без сжатия")
        assertTrue(events.none { it is ContextSummaryFinished }, "finished не должно быть при сбое")

        // fail-open: резюме не сохранено, контекст — вся история как при выключенном сжатии
        assertNull(compressionStore.getSummary("s3"))
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        // на момент построения контекста в истории 7 сообщений (6 посеянных + новый вопрос u4) + system
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user", "assistant", "user"),
            prompt.map { it["role"] })
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3", "a3", "u4"), prompt.drop(1).map { it["content"] })
        assertTrue(prompt.none { it["content"]?.startsWith("Резюме ранее") == true }, "резюме не должно попасть в контекст")
    }

    @Test
    fun `previous summary is passed labeled into summarization prompt`() {
        val (sessionStore, compressionStore) = stores("prev.db")
        compressionStore.updateSettings("s4", mapOf("enabled" to true, "keepLast" to 1, "summaryEvery" to 2))
        sessionStore.append("s4", "user", "u1")
        sessionStore.append("s4", "assistant", "a1")
        sessionStore.append("s4", "user", "u2")
        sessionStore.append("s4", "assistant", "a2")
        sessionStore.append("s4", "user", "u3")
        sessionStore.append("s4", "assistant", "a3")
        // считаем idx0..1 (u1,a1) уже свёрнутыми прежним резюме
        val oldUpto = sessionStore.getStored("s4")[1].id
        compressionStore.saveSummary("s4", "OLD", oldUpto)

        val llm = FakeSummaryLlm(
            summarize = { Flux.just(LlmEvent.ContentDelta("СВОДКА-NEW"), LlmEvent.Finished("stop")) }
        )
        val events = run(agent(llm, sessionStore, compressionStore), "s4", "u4")

        // total=7 (с вопросом u4), keepLast=1 → хвост (a3) и вопрос в резюмирование не идут;
        // foldable = не покрытые резюме вне хвоста (index<size-1-keep=5, id>idx1) → (u2,a2,u3) — 3 >= 2
        assertEquals(1, llm.summaryCalls.get())
        val summaryPrompt = llm.lastSummaryPrompt!!
        // промпт резюмирования: прежнее резюме + сворачиваемые сообщения + запрос сжатия последним
        assertEquals("user", summaryPrompt.first().role)
        assertEquals("Предыдущее резюме:\nOLD", summaryPrompt.first().content)
        assertEquals(listOf("user", "assistant", "user"), summaryPrompt.drop(1).dropLast(1).map { it.role })
        assertEquals(listOf("u2", "a2", "u3"), summaryPrompt.drop(1).dropLast(1).map { it.content })
        assertEquals("user", summaryPrompt.last().role)
        assertEquals(AgentImpl.SUMMARY_REQUEST_PROMPT, summaryPrompt.last().content)

        // контекст нового run: свежее резюме + последний хвост + вопрос
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals("Резюме ранее: СВОДКА-NEW", prompt[1]["content"])
        assertEquals(listOf("a3", "u4"), prompt.drop(2).map { it["content"] })
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `system notice is excluded from foldable count and from context`() {
        val (sessionStore, compressionStore) = stores("notice.db")
        compressionStore.updateSettings("s6", mapOf("enabled" to true, "keepLast" to 2, "summaryEvery" to 2))
        sessionStore.append("s6", "user", "u1")
        sessionStore.append("s6", "assistant", "a1")
        // служебная заметка о прошлом сжатии: не участвует ни в подсчёте foldable, ни в контексте
        sessionStore.append("s6", "system", "Сжатие контекста: 2 старых сообщений свернуто в резюме")
        sessionStore.append("s6", "user", "u2")

        val llm = FakeSummaryLlm(summarize = { Flux.error(IllegalStateException("не должен вызываться")) })
        val events = run(agent(llm, sessionStore, compressionStore), "s6", "u3")

        // не-системных stored = 4 (u1,a1,u2,u3), foldable = index<size-1-keep=1 → [u1] = 1 < summaryEvery=2;
        // если бы заметка учитывалась — stored=5, foldable=[u1,a1]=2 >= 2 и сжатие бы запустилось
        assertEquals(0, llm.summaryCalls.get())
        assertTrue(events.none { it.type == "context_summary_started" })
        assertTrue(events.none { it.type == "context_summary_finished" })
        assertNull(compressionStore.getSummary("s6"))

        // заметка не попала в контекст; резюме ещё нет → полная не-системная история + вопрос
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("system", "user", "assistant", "user", "user"), prompt.map { it["role"] })
        assertEquals(listOf("u1", "a1", "u2", "u3"), prompt.drop(1).map { it["content"] })

        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `disabled keeps full history and never compresses`() {
        val (sessionStore, compressionStore) = stores("disabled.db")
        // настройки не трогаем — по умолчанию enabled=false
        sessionStore.append("s5", "user", "u1")
        sessionStore.append("s5", "assistant", "a1")
        // служебная заметка о сжатии: в контекст (полная история) не попадает
        sessionStore.append("s5", "system", "Сжатие контекста: служебная заметка")
        sessionStore.append("s5", "user", "u2")

        val llm = FakeSummaryLlm(summarize = { Flux.error(IllegalStateException("не должен вызываться")) })
        val events = run(agent(llm, sessionStore, compressionStore), "s5", "u3")

        assertEquals(0, llm.summaryCalls.get())
        assertEquals(1, llm.mainCalls.get())
        assertTrue(events.none { it.type == "context_summary_started" })
        assertTrue(events.none { it.type == "context_summary_finished" })
        assertNull(compressionStore.getSummary("s5"))

        // поведение не изменилось: в контексте вся история вместе с новым вопросом
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("system", "user", "assistant", "user", "user"), prompt.map { it["role"] })
        assertEquals(listOf("u1", "a1", "u2", "u3"), prompt.drop(1).map { it["content"] })

        assertTrue(events.any { it is AgentFinished })
    }
}
