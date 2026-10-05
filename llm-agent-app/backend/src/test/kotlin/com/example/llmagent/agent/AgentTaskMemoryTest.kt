package com.example.llmagent.agent

import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.example.llmagent.agent.tools.TaskStateTool
import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.JdbcSessionLlmSettingsStore
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/**
 * Интеграционные тесты агентского цикла с памятью задачи (Day-25, task_memory):
 * - инъекция системного блока «=== ПАМЯТЬ ЗАДАЧИ ===» только при НЕпустом состоянии;
 * - событие task_memory_updated на нормальном пути — ДО agent_finished (после
 *   успешного извлечения состояние сохранено в хранилище);
 * - сбой извлечения (модель отдала мусор, включая ретрай) — fail-open: события нет,
 *   agent_finished приходит, прежнее состояние остаётся;
 * - накопление: промпт второго извлечения содержит прежнее состояние (JSON).
 * LLM — FakeToolCallLlmClient (маркер = TASK_MEMORY_REQUEST_PROMPT), SQLite — временный файл.
 */
class AgentTaskMemoryTest {

    @TempDir
    lateinit var tmpDir: Path

    private val sessionStore: SessionStore by lazy { SqliteTestSupport.store(tmpDir.resolve("agent-memory.db")) }
    private val om = ObjectMapper()
    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool(), TaskStateTool()))

    private fun newMemoryStore(name: String): JdbcTaskMemoryStore =
        JdbcTaskMemoryStore(SqliteTestSupport.jdbc(tmpDir.resolve(name)))

    private fun agent(llm: LlmClient, taskMemoryService: TaskMemoryService): AgentImpl {
        val llmProps = LlmProperties()
        val llmSettingsStore = JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("agent-memory-llm.db")))
        val sessionLlmSettings = SessionLlmSettingsProvider(llmSettingsStore, LlmSettings.from(llmProps))
        val compressionStore = SessionCompressionStore(SqliteTestSupport.jdbc(tmpDir.resolve("agent-memory-compression.db")))
        return AgentImpl(
            llm, tools, sessionStore, AgentProperties(8), LlmSettings.from(llmProps),
            sessionLlmSettings, compressionStore, om, taskMemoryService = taskMemoryService,
        )
    }

    private fun run(
        llm: FakeToolCallLlmClient,
        store: JdbcTaskMemoryStore,
        session: String,
        message: String,
    ): List<AgentEvent> =
        agent(llm, TaskMemoryService(store, om)).run(session, message).collectList().block(Duration.ofSeconds(10))!!

    /** FakeToolCallLlmClient с маркером извлечения памяти (замыкает промпт извлечения). */
    private fun llmWithExtraction(extractionJson: String, fallback: MockPlan = MockPlan.Text("Финальный ответ")) =
        FakeToolCallLlmClient(fallback = fallback)
            .mark(TaskMemoryService.TASK_MEMORY_REQUEST_PROMPT, MockPlan.Text(extractionJson))

    private fun memoryBlock(prompt: List<LlmMessage>): String? =
        prompt.filter { it.role == "system" }.mapNotNull { it.content }
            .firstOrNull { it.startsWith("=== ПАМЯТЬ ЗАДАЧИ ===") }

    @Test
    fun `run injects task memory system block when state non-empty`() {
        val store = newMemoryStore("atm-inject.db")
        store.upsert("atm-1", "Собрать отчёт", listOf("формат JSON"), listOf("не использовать БД"))
        val llm = llmWithExtraction("""{"goal":"Собрать отчёт","clarifications":["формат JSON"],"constraints":["не использовать БД"]}""")

        val events = run(llm, store, "atm-1", "Продолжим работу")

        // Промпт первого (основного) вызова содержит блок памяти задачи
        val block = memoryBlock(llm.prompts.first())
        assertTrue(block != null, "блок памяти задачи должен быть в промпте")
        assertTrue("Цель: Собрать отчёт" in block!!)
        assertTrue("Уточнено пользователем:\n1. формат JSON" in block)
        assertTrue("Ограничения и термины:\n1. не использовать БД" in block)

        // Событие пришло, состояние сохранено
        val updated = events.filterIsInstance<TaskMemoryUpdated>().single()
        assertEquals("Собрать отчёт", updated.goal)
        assertEquals("task-memory", updated.stepId)
    }

    @Test
    fun `run without task memory has no memory block`() {
        val store = newMemoryStore("atm-noblock.db")
        val llm = llmWithExtraction("""{"goal":"","clarifications":[],"constraints":[]}""")
        run(llm, store, "atm-2", "Привет")

        assertTrue(
            memoryBlock(llm.prompts.first()) == null,
            "без состояния памяти задачи блока быть не должно",
        )
    }

    @Test
    fun `extraction emits task_memory_updated before agent_finished and saves state`() {
        val store = newMemoryStore("atm-order.db")
        val llm = llmWithExtraction("""{"goal":"Новая цель","clarifications":["уточнение"],"constraints":[]}""")

        val events = run(llm, store, "atm-3", "Помоги собрать отчёт")

        val updated = events.filterIsInstance<TaskMemoryUpdated>().single()
        assertEquals("Новая цель", updated.goal)
        assertEquals(listOf("уточнение"), updated.clarifications)
        assertTrue(updated.constraints.isEmpty())

        // Событие ДО agent_finished (панель «Память задачи» обновляется к финализации)
        val types = events.map { it.type }
        assertTrue("task_memory_updated" in types)
        assertTrue("agent_finished" in types)
        assertTrue(
            types.indexOf("task_memory_updated") < types.indexOf("agent_finished"),
            "task_memory_updated должен приходить раньше agent_finished",
        )

        // Извлечение — отдельный LLM-вызов после основного
        assertEquals(2, llm.callCount.get(), "основной вызов + вызов извлечения")
        val saved = store.get("atm-3")
        assertEquals("Новая цель", saved.goal)
        assertTrue(!saved.isEmpty())
    }

    @Test
    fun `extraction failure keeps previous state and skips event`() {
        val store = newMemoryStore("atm-fail.db")
        store.upsert("atm-4", "Прежняя цель", emptyList(), emptyList())
        val llm = llmWithExtraction("совсем не json")

        val events = run(llm, store, "atm-4", "Что нового?")

        // Fail-open: события нет, пузырь закрылся нормально
        assertTrue(events.filterIsInstance<TaskMemoryUpdated>().isEmpty(), "при сбое извлечения события быть не должно")
        assertTrue(events.any { it is AgentFinished })

        // Ретрай случился (основной вызов + извлечение + ретрай), прежнее состояние НЕ тронуто
        assertEquals(3, llm.callCount.get(), "основной вызов + извлечение + ретрай")
        assertEquals("Прежняя цель", store.get("atm-4").goal, "прежнее состояние сохраняется при сбое")
    }

    @Test
    fun `second extraction prompt contains previous state`() {
        val store = newMemoryStore("atm-accum.db")
        val llm = FakeToolCallLlmClient(fallback = MockPlan.Text("(fake: сценарий не задан)"))
            .mark(
                TaskMemoryService.TASK_MEMORY_REQUEST_PROMPT,
                MockPlan.Text("""{"goal":"","clarifications":[],"constraints":[]}"""),
            )

        // Первый обмен: извлечение вернуло цель
        llm.mark(
            TaskMemoryService.TASK_MEMORY_REQUEST_PROMPT,
            MockPlan.Text("""{"goal":"Цель 1","clarifications":[],"constraints":[]}"""),
        )
        run(llm, store, "atm-5", "первый вопрос")

        // Второй обмен: извлечение возвращает обновлённую цель
        llm.mark(
            TaskMemoryService.TASK_MEMORY_REQUEST_PROMPT,
            MockPlan.Text("""{"goal":"Цель 2","clarifications":[],"constraints":[]}"""),
        )
        run(llm, store, "atm-5", "второй вопрос")

        // Вызовы: main1, extraction1, main2, extraction2
        assertEquals(4, llm.prompts.size)
        val secondExtraction = llm.prompts[3]
        val previousState = secondExtraction.first().content!!
        assertTrue(
            previousState.startsWith("Текущее состояние памяти задачи (JSON)"),
            "первое сообщение промпта извлечения — прежнее состояние",
        )
        assertTrue("Цель 1" in previousState, "в промпте второго извлечения — цель из первого обмена")
        assertTrue("второй вопрос" in secondExtraction[1].content!!)
    }
}
