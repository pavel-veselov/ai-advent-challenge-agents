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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты memory layers в AgentImpl (day-13: память пишется ТОЛЬКО пользователем):
 * агент больше НЕ захватывает задачу на старте run и НЕ добавляет заметки после
 * tool-результатов (авто-записи удалены, tool memory_save удалён); контекстные блоки
 * WM/LTM подаются модели ИЗ ПОЛЬЗОВАТЕЛЬСКИХ данных (user-заметки / upsert LTM),
 * omit-empty при пустой памяти и fail-open при отсутствующем/битом хранилище.
 * memory_updated агентом НЕ эмитится.
 *
 * Паттерн — AgentStickyFactsTest: AgentImpl конструктором с позиционными (nullable)
 * store'ами, SqliteTestSupport @TempDir, фейковые LLM; tool-цикл — через
 * FakeToolCallLlmClient. Фикстуры создают проект + сессии поверх chat_sessions.
 */
class AgentMemoryLayersTest {

    @TempDir
    lateinit var tmpDir: Path

    private val om = ObjectMapper()

    private data class MemoryStores(
        val dbFile: Path,
        val session: SessionStore,
        val projects: ProjectStore,
        val compression: SessionCompressionStore,
        val context: SessionContextStore,
        val facts: SessionFactsStore,
        val branches: SessionBranchStore,
        val workingMemory: WorkingMemoryStore,
        val longTerm: LongTermMemoryStore,
    )

    /** Store'ы на уникальном SQLite-файле (каждый тест — своя БД), включая projects/chat_sessions. */
    private fun stores(dbHint: String): MemoryStores {
        val dbFile = tmpDir.resolve("$dbHint-${UUID.randomUUID()}.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        SqliteTestSupport.createChatMessagesTable(jdbc)
        // Порядок важен: ProjectStore создаёт chat_sessions, чтобы SessionStore-фикстура
        // ниже видела реестр сессий (миграция-очистка на пустой БД ничего не удаляет).
        val projects = ProjectStore(jdbc)
        val branches = SessionBranchStore(jdbc)
        return MemoryStores(
            dbFile = dbFile,
            session = SessionStore(jdbc, branchStore = branches),
            projects = projects,
            compression = SessionCompressionStore(jdbc),
            context = SessionContextStore(jdbc),
            facts = SessionFactsStore(jdbc),
            branches = branches,
            workingMemory = WorkingMemoryStore(jdbc),
            longTerm = LongTermMemoryStore(jdbc),
        )
    }

    /** Создаёт проект и в нём сессию; возвращает (projectId, sessionId). */
    private fun projectAndSession(s: MemoryStores): Pair<Long, String> {
        val projectId = s.projects.create("тест-проект")
        val sessionId = s.session.createSession(projectId, "сессия")
        return projectId to sessionId
    }

    /**
     * AgentImpl c WM/LTM store'ами (по умолчанию — реальные из [s]; null — память отключена,
     * как в старых тестах). ToolRegistry — только calculator/get_current_datetime
     * (инструмент memory_save УДАЛЁН: память пишется только пользователем).
     */
    private fun agent(
        llm: LlmClient,
        s: MemoryStores,
        wm: WorkingMemoryStore? = s.workingMemory,
        ltm: LongTermMemoryStore? = s.longTerm,
    ): AgentImpl {
        val llmProps = LlmProperties()
        val agentProps = AgentProperties(8)
        val sessionLlmSettings = SessionLlmSettingsProvider(
            JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("mem-llm-${UUID.randomUUID()}.db"))),
            LlmSettings.from(llmProps),
        )
        val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool()))
        return AgentImpl(
            llm, tools, s.session, agentProps, LlmSettings.from(llmProps), sessionLlmSettings,
            s.compression, om, s.context, s.facts, s.branches, wm, ltm,
        )
    }

    private fun run(a: AgentImpl, sessionId: String, message: String): List<AgentEvent> =
        a.run(sessionId, message).collectList().block(Duration.ofSeconds(10))!!

    private fun promptContent(events: List<AgentEvent>): String =
        events.filterIsInstance<LlmRequestStarted>().single().prompt
            .mapNotNull { it["content"] }
            .joinToString("\n")

    // ------------------------------------------------------------------
    // 1. Инъекция: первый снимок llm_request_started содержит блоки WM (проекта) и LTM
    //    (после system, до истории) — из ПОЛЬЗОВАТЕЛЬСКИХ данных (заметки/upsert).
    // ------------------------------------------------------------------
    @Test
    fun `wm and ltm blocks injected between system prompt and history`() {
        val s = stores("inj")
        val (projectId, sessionId) = projectAndSession(s)
        // данные добавляет ПОЛЬЗОВАТЕЛЬ (тот же путь, что REST notes / long-term)
        s.workingMemory.setTask(projectId.toString(), "мой тест")
        s.workingMemory.appendNote(projectId.toString(), "первый шаг сделан")
        s.workingMemory.appendNote(projectId.toString(), "второй шаг сделан")
        s.longTerm.upsert("s0", "profile", "автор", "Аня")

        val llm = FakeToolCallLlmClient()
        val events = run(agent(llm, s), sessionId, "привет")

        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertEquals("system", prompt[0]["role"])
        // блок WM на позиции 1, блок LTM на позиции 2 — после system, до истории
        val wmContent = prompt[1]["content"]!!
        val ltmContent = prompt[2]["content"]!!
        assertTrue(wmContent.startsWith("=== РАБОЧАЯ ПАМЯТЬ ===\nТекущая задача: мой тест"), wmContent)
        assertTrue(wmContent.contains("Промежуточные результаты:"), wmContent)
        assertTrue(wmContent.contains("1. первый шаг сделан"), wmContent)
        assertTrue(wmContent.contains("2. второй шаг сделан"), wmContent)
        assertTrue(ltmContent.startsWith("=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===\nprofile | автор: Аня"), ltmContent)
        // история — следом за блоками
        assertEquals("user", prompt[3]["role"])
        assertEquals("привет", prompt[3]["content"])
        assertTrue(events.any { it is AgentFinished })
    }

    // ------------------------------------------------------------------
    // 2. Общая WM проекта: ДВЕ сессии одного проекта видят одну и ту же рабочую память.
    // ------------------------------------------------------------------
    @Test
    fun `both sessions of the same project share the working memory block`() {
        val s = stores("shared")
        val (projectId, sessionA) = projectAndSession(s)
        val sessionB = s.session.createSession(projectId, "вторая сессия")
        s.workingMemory.setTask(projectId.toString(), "задача проекта")

        val llm = FakeToolCallLlmClient()
        val promptA = run(agent(llm, s), sessionA, "вопрос из A").filterIsInstance<LlmRequestStarted>().single().prompt
        val promptB = run(agent(llm, s), sessionB, "вопрос из B").filterIsInstance<LlmRequestStarted>().single().prompt

        val wmA = promptA.first { it["content"]?.startsWith("=== РАБОЧАЯ ПАМЯТЬ ===") == true }["content"]!!
        val wmB = promptB.first { it["content"]?.startsWith("=== РАБОЧАЯ ПАМЯТЬ ===") == true }["content"]!!
        assertTrue(wmA.contains("Текущая задача: задача проекта"), wmA)
        assertEquals(wmA, wmB, "WM проекта общая — обе сессии видят один и тот же блок")
    }

    // ------------------------------------------------------------------
    // 3. Авто-записи УДАЛЕНЫ: агент НЕ захватывает задачу и НЕ пишет заметки после
    //    успешного tool-результата; memory_updated не эмитится; с пустой пользовательской
    //    памятью контекстные блоки omit-ятся (store подключён, данных нет).
    // ------------------------------------------------------------------
    @Test
    fun `agent run does not auto write working memory task or notes`() {
        val s = stores("noauto")
        val (projectId, sessionId) = projectAndSession(s)
        val llm = FakeToolCallLlmClient(
            listOf(
                MockPlan.ToolCall("calculator", """{"expression":"2+2"}"""),
                MockPlan.Text("Ответ 4"),
            )
        )

        val events = run(agent(llm, s), sessionId, "сколько будет 2+2?")

        // Доказательство успешного tool-раунда (иначе «ничего не писал» — не доказ.)
        assertTrue(events.any { it is ToolCallFinished && it.status == "success" })
        assertTrue(events.any { it is AgentFinished })
        // агент НЕ записал ни задачу, ни заметку
        val mem = s.workingMemory.get(projectId.toString())
        assertTrue(mem.task == null, "агент не должен захватывать задачу, а в памяти: ${mem.task}")
        assertTrue(mem.notes.isEmpty(), "агент не должен писать заметки после tool, а в памяти: ${mem.notes}")
        // memory_updated агентом больше не эмитится (удалены авто-записи, на которые он был завязан)
        assertTrue(events.none { it is MemoryUpdated }, "агент не должен слать memory_updated")

        // с пустой пользовательской памятью блоки в контекст не подаются (omit-empty)
        val prompt = events.filterIsInstance<LlmRequestStarted>().last().prompt
        assertTrue(prompt.none { it["content"]?.startsWith("=== РАБОЧАЯ ПАМЯТЬ ===") == true })
        assertTrue(prompt.none { it["content"]?.startsWith("=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===") == true })
    }

    // ------------------------------------------------------------------
    // 4. Пользовательская заметка (appendNote — тот же путь, что REST notes) появляется
    //    в блоке WM СЛЕДУЮЩЕГО запроса; агент не добавляет ничего сверху.
    // ------------------------------------------------------------------
    @Test
    fun `user appended note appears in the working memory block of the next request`() {
        val s = stores("usernote")
        val (projectId, sessionId) = projectAndSession(s)
        s.workingMemory.appendNote(projectId.toString(), "пользователь сам написал заметку")

        val llm = FakeToolCallLlmClient()
        val events = run(agent(llm, s), sessionId, "привет")

        val content = promptContent(events)
        assertTrue(content.contains("=== РАБОЧАЯ ПАМЯТЬ ==="), content)
        assertTrue(content.contains("пользователь сам написал заметку"), content)
        // ровно то, что добавил пользователь — ничего сверх
        assertEquals(1, s.workingMemory.get(projectId.toString()).notes.size)
    }

    // ------------------------------------------------------------------
    // 5. Пользовательская LTM-запись (upsert — тот же путь, что REST long-term)
    //    появляется в блоке LTM (top-20).
    // ------------------------------------------------------------------
    @Test
    fun `user appended ltm entry appears in the long term memory block`() {
        val s = stores("userltm")
        val (_, sessionId) = projectAndSession(s)
        s.longTerm.upsert("s0", "profile", "язык", "Kotlin")

        val llm = FakeToolCallLlmClient()
        val events = run(agent(llm, s), sessionId, "привет")

        val content = promptContent(events)
        assertTrue(content.contains("=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===\nprofile | язык: Kotlin"), content)
    }

    // ------------------------------------------------------------------
    // 6. Лимит: 25 записей LTM → в блоке ровно 20 строк-записей + строка
    //    «…и ещё 5 записей в долговременной памяти».
    // ------------------------------------------------------------------
    @Test
    fun `ltm list capped to 20 entries with overflow line`() {
        val s = stores("cap")
        val (_, sessionId) = projectAndSession(s)
        for (i in 1..25) s.longTerm.upsert("s0", "profile", "k$i", "v$i")

        val llm = FakeToolCallLlmClient()
        val events = run(agent(llm, s), sessionId, "привет")

        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        val ltm = prompt
            .mapNotNull { it["content"] }
            .first { it.startsWith("=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===") }
        val lines = ltm.split("\n")
        val entryLines = lines.drop(1).filter { it.contains(" | ") }
        assertEquals(20, entryLines.size)
        assertEquals("…и ещё 5 записей в долговременной памяти", lines.last())
    }

    // ------------------------------------------------------------------
    // 7. Разделение хранилищ (STM vs WM vs LTM): сообщение сессии не видно в WM/LTM,
    //    заметки WM не попадают в сообщения/LTM, LTM-запись видна в ДРУГОМ проекте, но
    //    не в его chat_messages/WM.
    // ------------------------------------------------------------------
    @Test
    fun `stm wm and ltm are separate storages`() {
        val s = stores("sep")
        val (projectA, sessionA) = projectAndSession(s)
        val (projectB, sessionB) = projectAndSession(s)
        val historyMarker = "МАРКЕР-ИСТОРИЯ"
        val wmMarker = "МАРКЕР-РАБОЧАЯ"
        val ltmMarker = "МАРКЕР-ДОЛГОВРЕМЕННАЯ"

        // пишем каждый маркер ТОЛЬКО в свой слой
        s.session.append(sessionA, "user", historyMarker)               // STM (chat_messages), сессия A
        s.workingMemory.appendNote(projectA.toString(), wmMarker)       // WM проекта A
        s.longTerm.upsert("src", "knowledge", "sep-key", ltmMarker)     // LTM (глобальная)

        // run в ДРУГОМ проекте (B): глобальная LTM видна, WM A и сообщение A — нет
        val events = run(agent(FakeToolCallLlmClient(), s), sessionB, "вопрос из B")
        val content = promptContent(events)

        // LTM глобальна — видна в проекте B как блок долговременной памяти
        assertTrue(content.contains("=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ==="), content)
        assertTrue(content.contains(ltmMarker), content)
        // сообщение сессии A в контекст сессии B не попадает
        assertFalse(content.contains(historyMarker), "STM сессии A не должна течь в сессию B: $content")
        // WM проекта A в проект B не течёт
        assertFalse(content.contains(wmMarker), "WM проекта A не должна течь в проект B: $content")

        // прямой контроль хранилищ: маркер живёт ТОЛЬКО в своём слое
        assertTrue(s.session.getStored(sessionA).any { it.content.contains(historyMarker) })
        assertTrue(s.workingMemory.get(projectA.toString()).notes.any { it.contains(wmMarker) })
        assertTrue(s.longTerm.listAll().any { it.value.contains(ltmMarker) })
        // WM-заметка не попадает ни в сообщения (обе сессии), ни в LTM
        assertTrue(s.session.getStored(sessionA).none { it.content.contains(wmMarker) })
        assertTrue(s.session.getStored(sessionB).none { it.content.contains(wmMarker) })
        assertTrue(s.longTerm.listAll().none { it.value.contains(wmMarker) })
        // LTM-запись не попадает в chat_messages и в WM проекта A (и B осталась пустой)
        assertTrue(s.session.getStored(sessionA).none { it.content.contains(ltmMarker) })
        assertTrue(s.workingMemory.get(projectA.toString()).notes.none { it.contains(ltmMarker) })
        assertTrue(s.workingMemory.get(projectB.toString()).notes.isEmpty())
        // history-маркер не попадает в WM/LTM
        assertTrue(s.workingMemory.get(projectA.toString()).notes.none { it.contains(historyMarker) })
        assertTrue(s.longTerm.listAll().none { it.value.contains(historyMarker) })
    }

    // ------------------------------------------------------------------
    // 8. Fail-open: отсутствующее хранилище — run завершается без блоков памяти.
    // ------------------------------------------------------------------
    @Test
    fun `absent memory stores fail open and run completes`() {
        val s = stores("absent")
        val (_, sessionId) = projectAndSession(s)
        val llm = FakeToolCallLlmClient()

        val events = run(agent(llm, s, wm = null, ltm = null), sessionId, "привет")

        assertTrue(events.any { it is AgentFinished })
        assertTrue(events.filterIsInstance<LlmRequestStarted>().single().prompt.none { it["content"]?.startsWith("=== ") == true })
    }

    // ------------------------------------------------------------------
    // 9. Fail-open: хранилище, которое падает (таблицы памяти удалены) —
    //     память-операции не роняют run: ответ получен, контекст без блоков.
    // ------------------------------------------------------------------
    @Test
    fun `failing memory stores fail open and run completes`() {
        val s = stores("failing")
        val (_, sessionId) = projectAndSession(s)
        // Уроним только таблицы памяти: SessionStore (chat_messages) жив,
        // а все memory-чтения/записи начинают падать внутри store (fail-open -> warn).
        SqliteTestSupport.jdbc(s.dbFile).let { jdbc ->
            jdbc.execute("DROP TABLE IF EXISTS agent_working_memory")
            jdbc.execute("DROP TABLE IF EXISTS agent_long_term_memory")
        }

        val llm = FakeToolCallLlmClient()
        val events = run(agent(llm, s), sessionId, "вопрос после поломки")

        // run завершился, блоков памяти в контексте нет (чтение упало -> omit)
        assertTrue(events.any { it is AgentFinished }, "run должен завершиться при упавшей памяти")
        val prompt = events.filterIsInstance<LlmRequestStarted>().single().prompt
        assertTrue(prompt.none { it["content"]?.startsWith("=== РАБОЧАЯ ПАМЯТЬ ===") == true })
        assertTrue(prompt.none { it["content"]?.startsWith("=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===") == true })
    }
}
