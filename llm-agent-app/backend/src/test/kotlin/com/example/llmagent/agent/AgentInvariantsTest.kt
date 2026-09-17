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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты инъекции инвариантов проекта в AgentImpl (day-14): системный блок
 * «=== ИНВАРИАНТЫ ===» подаётся модели, когда в InvariantsStore есть записи для
 * проекта; блок содержит текст инвариантов в формате «категория — текст» и явную
 * инструкцию об отказе от решений, нарушающих инвариант. Инварианты скоупятся ПО
 * ПРОЕКТУ и хранятся отдельно от диалога. Fail-open: нет store / нет записей /
 * сбой чтения — блок молча пропускается, run не ломается.
 *
 * Паттерн — AgentMemoryLayersTest: AgentImpl конструктором с позиционными (nullable)
 * store'ами, SqliteTestSupport @TempDir, фейковый LLM (FakeToolCallLlmClient).
 */
class AgentInvariantsTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()

    private data class InvariantsStores(
        val dbFile: Path,
        val session: SessionStore,
        val projects: ProjectStore,
        val compression: SessionCompressionStore,
        val context: SessionContextStore,
        val facts: SessionFactsStore,
        val branches: SessionBranchStore,
        val workingMemory: WorkingMemoryStore,
        val longTerm: LongTermMemoryStore,
        val invariants: InvariantsStore,
    )

    /** Store'ы на уникальном SQLite-файле (каждый тест — своя БД), включая projects/chat_sessions. */
    private fun stores(dbHint: String): InvariantsStores {
        val dbFile = tmpDir.resolve("$dbHint-${UUID.randomUUID()}.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        SqliteTestSupport.createChatMessagesTable(jdbc)
        val projects = ProjectStore(jdbc)
        val branches = SessionBranchStore(jdbc)
        return InvariantsStores(
            dbFile = dbFile,
            session = SessionStore(jdbc, branchStore = branches),
            projects = projects,
            compression = SessionCompressionStore(jdbc),
            context = SessionContextStore(jdbc),
            facts = SessionFactsStore(jdbc),
            branches = branches,
            workingMemory = WorkingMemoryStore(jdbc),
            longTerm = LongTermMemoryStore(jdbc),
            invariants = InvariantsStore(jdbc),
        )
    }

    /** Создаёт проект и в нём сессию; возвращает (projectId, sessionId). */
    private fun projectAndSession(s: InvariantsStores): Pair<Long, String> {
        val projectId = s.projects.create("тест-проект")
        val sessionId = s.session.createSession(projectId, "сессия")
        return projectId to sessionId
    }

    /**
     * AgentImpl c WM/LTM/инвариантами store'ами (по умолчанию — реальные из [s];
     * null — фича отключена, как в старых тестах).
     */
    private fun agent(
        llm: LlmClient,
        s: InvariantsStores,
        invariants: InvariantsStore? = s.invariants,
    ): AgentImpl {
        val llmProps = LlmProperties()
        val agentProps = AgentProperties(8)
        val sessionLlmSettings = SessionLlmSettingsProvider(
            JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("inv-llm-${UUID.randomUUID()}.db"))),
            LlmSettings.from(llmProps),
        )
        val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))
        return AgentImpl(
            llm, tools, s.session, agentProps, LlmSettings.from(llmProps), sessionLlmSettings,
            s.compression, om, s.context, s.facts, s.branches, s.workingMemory, s.longTerm,
            null, null, null, null, invariants,
        )
    }

    private fun run(a: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        a.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!

    private fun promptContent(events: List<AgentEvent>): String =
        events.filterIsInstance<LlmRequestStarted>().single().prompt
            .mapNotNull { it["content"] }
            .joinToString("\n")

    // ------------------------------------------------------------------
    // 1. Инъекция: при наличии инвариантов проекта системный блок «=== ИНВАРИАНТЫ ===»
    //    попадает в контекст (после памяти, до истории) с текстом и инструкцией об отказе.
    // ------------------------------------------------------------------
    @Test
    fun `invariants block injected with text and refusal instruction`() {
        val s = stores("inv-inj")
        val (projectId, sessionId) = projectAndSession(s)
        s.invariants.add(projectId.toString(), "Ограничения по стеку", "Использовать только PostgreSQL, не MongoDB")
        s.invariants.add(projectId.toString(), "Техническое решение", "Писать на TypeScript, не Python")

        val llm = FakeToolCallLlmClient()
        val events = run(agent(llm, s), sessionId, "привет")

        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        // блок инвариантов — последний system-блок перед историей
        val invContent = prompt
            .mapNotNull { it["content"] }
            .first { it.startsWith("=== ИНВАРИАНТЫ ===") }
        assertTrue(invContent.startsWith("=== ИНВАРИАНТЫ ===\n"), invContent)
        assertTrue(invContent.contains("1. Ограничения по стеку — Использовать только PostgreSQL, не MongoDB"), invContent)
        assertTrue(invContent.contains("2. Техническое решение — Писать на TypeScript, не Python"), invContent)
        // явная инструкция об обязательности и отказе
        assertTrue(invContent.contains("Эти инварианты обязательны"), invContent)
        assertTrue(invContent.contains("откажись и объясни, какой именно инвариант нарушается"), invContent)
        // история следом
        assertTrue(events.any { it is AgentFinished })
    }

    // ------------------------------------------------------------------
    // 2. Инварианты одного проекта НЕ попадают в сессию другого проекта (scoped per project).
    // ------------------------------------------------------------------
    @Test
    fun `invariants are scoped per project`() {
        val s = stores("inv-scope")
        val (projectA, sessionA) = projectAndSession(s)
        val (projectB, sessionB) = projectAndSession(s)
        s.invariants.add(projectA.toString(), "Стек", "только для проекта A")

        val eventsA = run(agent(FakeToolCallLlmClient(), s), sessionA, "вопрос из A")
        assertTrue(promptContent(eventsA).contains("=== ИНВАРИАНТЫ ==="), "проект A видит свой инвариант")

        val eventsB = run(agent(FakeToolCallLlmClient(), s), sessionB, "вопрос из B")
        assertFalse(promptContent(eventsB).contains("=== ИНВАРИАНТЫ ==="), "проект B не видит инвариант проекта A")
    }

    // ------------------------------------------------------------------
    // 3. Пустой список инвариантов — блока нет, run работает штатно.
    // ------------------------------------------------------------------
    @Test
    fun `no invariants means no invariants block`() {
        val s = stores("inv-none")
        val (_, sessionId) = projectAndSession(s)

        val events = run(agent(FakeToolCallLlmClient(), s), sessionId, "привет")
        assertFalse(promptContent(events).contains("=== ИНВАРИАНТЫ ==="))
        assertTrue(events.any { it is AgentFinished })
    }

    // ------------------------------------------------------------------
    // 4. Fail-open: store не подключён (null) — блока нет, run не ломается.
    // ------------------------------------------------------------------
    @Test
    fun `missing invariants store fails open and run completes`() {
        val s = stores("inv-off")
        val (_, sessionId) = projectAndSession(s)
        s.invariants.add("999", null, "инвариант другого проекта")

        val events = run(agent(FakeToolCallLlmClient(), s, invariants = null), sessionId, "привет")
        assertFalse(promptContent(events).contains("=== ИНВАРИАНТЫ ==="))
        assertTrue(events.any { it is AgentFinished })
    }

    // ------------------------------------------------------------------
    // 5. Fail-open: хранилище, которое падает (таблица инвариантов удалена) —
    //    блок пропускается, run завершается.
    // ------------------------------------------------------------------
    @Test
    fun `failing invariants store fails open and run completes`() {
        val s = stores("inv-fail")
        val (_, sessionId) = projectAndSession(s)
        s.invariants.add("999", null, "инвариант")
        // Уроним таблицу инвариантов: чтение начнёт падать внутри store (fail-open -> warn).
        SqliteTestSupport.jdbc(s.dbFile).let { jdbc ->
            jdbc.execute("DROP TABLE IF EXISTS agent_invariants")
        }

        val events = run(agent(FakeToolCallLlmClient(), s), sessionId, "вопрос после поломки")
        assertTrue(events.any { it is AgentFinished }, "run должен завершиться при упавшем хранилище")
        assertFalse(promptContent(events).contains("=== ИНВАРИАНТЫ ==="))
    }
}
