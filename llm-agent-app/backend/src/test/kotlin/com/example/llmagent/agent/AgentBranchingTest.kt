package com.example.llmagent.agent

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

/** LLM, всегда отвечающий фиксированным текстом (ветки не требуют инструментов). */
private class FixedLlm(private val text: String = "ответ ветки") : LlmClient {
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

private data class BranchingStores(
    val session: SessionStore,
    val compression: SessionCompressionStore,
    val context: SessionContextStore,
    val facts: SessionFactsStore,
    val branches: SessionBranchStore,
)

/** Юнит-тесты стратегии branching: контекст = цепочка активной ветки (без других веток). */
class AgentBranchingTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()

    private fun stores(dbHint: String): BranchingStores {
        val dbFile = tmpDir.resolve("$dbHint-${UUID.randomUUID()}.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        SqliteTestSupport.createChatMessagesTable(jdbc)
        val branches = SessionBranchStore(jdbc)
        return BranchingStores(
            session = SessionStore(jdbc, branchStore = branches),
            compression = SessionCompressionStore(jdbc),
            context = SessionContextStore(jdbc),
            facts = SessionFactsStore(jdbc),
            branches = branches,
        )
    }

    private fun agent(llm: LlmClient, s: BranchingStores): AgentImpl {
        val llmProps = LlmProperties()
        val agentProps = AgentProperties(8)
        val sessionLlmSettings = SessionLlmSettingsProvider(
            JdbcSessionLlmSettingsStore(
                SqliteTestSupport.jdbc(tmpDir.resolve("br-llm-${UUID.randomUUID()}.db"))
            ),
            LlmSettings.from(llmProps),
        )
        return AgentImpl(
            llm, ToolRegistry(emptyList()), s.session, agentProps,
            LlmSettings.from(llmProps), sessionLlmSettings, s.compression, om, s.context, s.facts, s.branches,
        )
    }

    private fun run(a: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        a.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!

    private fun seed(s: BranchingStores, sessionId: String) {
        s.session.append(sessionId, "user", "u1")
        s.session.append(sessionId, "assistant", "a1")
        s.session.append(sessionId, "user", "u2")
        s.session.append(sessionId, "assistant", "a2")
    }

    @Test
    fun `context of branch after fork contains only its own chain and shared ancestors`() {
        val s = stores("brA")
        s.context.update("s", mapOf("strategy" to "branching"))
        seed(s, "s")

        // «вилка» от a2: новая ветка B активна; «Основная» остаётся с головой a2
        val forkId = s.session.getStored("s")[3].id
        val b = s.branches.create("s", "Ветка 2", forkId)
        s.branches.setActive("s", b.id)

        // два сообщения в ветку B
        run(agent(FixedLlm("r1"), s), "s", "q1")
        run(agent(FixedLlm("r1"), s), "s", "q2")

        // переключаемся на «Основную» и пишем там
        val main = s.branches.list("s").first { it.name == "Основная" }
        s.branches.setActive("s", main.id)
        val llm = FixedLlm("rA")
        val events = run(agent(llm, s), "s", "вопрос в основной")

        // контекст «Основной»: общие предки u1..a2 + вопрос; q1/q2/r1 (из B) НЕ входят
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("u1", "a1", "u2", "a2", "вопрос в основной"), prompt.drop(1).map { it["content"] })
        assertTrue(prompt.none { it["content"] in listOf("q1", "q2", "r1") })

        assertEquals("branching", events.filterIsInstance<AgentStarted>().single().settings["contextStrategy"])
        assertTrue(events.any { it is AgentFinished })
    }

    @Test
    fun `branch context follows active branch and keeps chronological order root to question`() {
        val s = stores("brB")
        s.context.update("s", mapOf("strategy" to "branching"))
        seed(s, "s")

        // форкаем от a2 и пишем в ветку B
        val forkId = s.session.getStored("s")[3].id
        val b = s.branches.create("s", "Ветка 2", forkId)
        s.branches.setActive("s", b.id)
        run(agent(FixedLlm("r1"), s), "s", "q1")

        // вопрос в той же ветке B — контекст: предки + q1 + r1 + вопрос
        val llm = FixedLlm("r2")
        val events = run(agent(llm, s), "s", "вопрос2")
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("u1", "a1", "u2", "a2", "q1", "r1", "вопрос2"), prompt.drop(1).map { it["content"] })
    }

    @Test
    fun `default branch is used when strategy branching and history linear`() {
        val s = stores("brC")
        s.context.update("s", mapOf("strategy" to "branching"))
        seed(s, "s")

        val llm = FixedLlm("r")
        val events = run(agent(llm, s), "s", "вопрос")
        // активной ветки нет → действует «Основная»: полная линейная цепочка + вопрос
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals(listOf("u1", "a1", "u2", "a2", "вопрос"), prompt.drop(1).map { it["content"] })

        val branch = s.branches.effectiveBranch("s")
        assertTrue(branch != null && branch.name == "Основная")
        // голова «Основной» — последнее сообщение (вопрос)
        assertEquals(llm.lastMainPrompt?.lastOrNull()?.content, "вопрос")
    }
}
