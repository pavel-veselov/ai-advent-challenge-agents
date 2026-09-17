package com.example.llmagent.agent

import com.example.llmagent.agent.tools.CalculatorTool
import com.example.llmagent.agent.tools.GetCurrentDateTimeTool
import com.example.llmagent.agent.tools.TaskStateTool
import com.example.llmagent.config.AgentProperties
import com.example.llmagent.config.JdbcAppSettingsStore
import com.example.llmagent.config.JdbcSessionLlmSettingsStore
import com.example.llmagent.config.LlmProperties
import com.example.llmagent.config.LlmSettings
import com.example.llmagent.config.SessionLlmSettingsProvider
import com.example.llmagent.config.WorkflowSettings
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Юнит-тесты агентского цикла с инструментом task_state (Day-13):
 * - перехват вызова по имени (TaskStateTool.execute недоступен sessionId — исполняет агент);
 * - валидация FSM: ошибка перехода уходит модели ToolResult-ошибкой, модель корректируется;
 * - событие task_state_changed — только после УСПЕШНОГО обновления (stepId "task-state");
 * - системный блок «=== СОСТОЯНИЕ ЗАДАЧИ ===» в промпте следующего run, включая строку
 *   паузы; без состояния блок отсутствует.
 * LLM — FakeToolCallLlmClient (скриптованный tool-цикл), SQLite — временный файл.
 */
class AgentTaskStateTest {

    @TempDir
    lateinit var tmpDir: Path

    private val sessionStore: SessionStore by lazy { SqliteTestSupport.store(tmpDir.resolve("agent-task.db")) }
    private val om = ObjectMapper()
    private val tools = ToolRegistry(listOf(CalculatorTool(), GetCurrentDateTimeTool(), TaskStateTool()))

    private fun newStore(name: String): TaskStateStore =
        TaskStateStore(SqliteTestSupport.jdbc(tmpDir.resolve(name)))

    /** WorkflowSettings поверх отдельного временного файла app_settings. */
    private fun workflowSettings(enabled: Boolean, mode: String): WorkflowSettings =
        WorkflowSettings(JdbcAppSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("agent-task-wf-$mode.db"))))
            .also { it.set(enabled, mode) }

    private fun agent(llm: LlmClient, store: TaskStateStore, workflowSettings: WorkflowSettings? = null): AgentImpl {
        val llmProps = LlmProperties()
        val llmSettingsStore = JdbcSessionLlmSettingsStore(SqliteTestSupport.jdbc(tmpDir.resolve("agent-task-llm.db")))
        val sessionLlmSettings = SessionLlmSettingsProvider(llmSettingsStore, LlmSettings.from(llmProps))
        val compressionStore = SessionCompressionStore(SqliteTestSupport.jdbc(tmpDir.resolve("agent-task-compression.db")))
        return AgentImpl(
            llm, tools, sessionStore, AgentProperties(8), LlmSettings.from(llmProps),
            sessionLlmSettings, compressionStore, om, taskStateStore = store, workflowSettings = workflowSettings,
        )
    }

    private fun run(
        llm: LlmClient,
        store: TaskStateStore,
        session: String,
        message: String,
        workflowSettings: WorkflowSettings? = null,
    ): List<AgentEvent> =
        agent(llm, store, workflowSettings).run(session, message).collectList().block(Duration.ofSeconds(10))!!

    @Test
    fun `task_state tool updates state and emits task_state_changed`() {
        val store = newStore("at-create.db")
        val llm = FakeToolCallLlmClient(
            listOf(
                MockPlan.ToolCall(
                    "task_state",
                    """{"stage":"planning","current_step":"уточнить цель","expected_action":"спросить пользователя"}""",
                ),
                MockPlan.Text("Начал планирование задачи"),
            ),
        )

        val events = run(llm, store, "at-1", "Помоги спланировать задачу")

        val types = events.map { it.type }
        assertTrue("tool_call_started" in types)
        assertTrue("tool_call_finished" in types)

        val toolStarted = events.filterIsInstance<ToolCallStarted>().single()
        assertEquals("task_state", toolStarted.toolName)

        val toolFinished = events.filterIsInstance<ToolCallFinished>().single()
        assertEquals("success", toolFinished.status)

        // Событие после успешного сохранения: этап/шаг/действие/пауза, фиксированный stepId
        val changed = events.filterIsInstance<TaskStateChanged>().single()
        assertEquals("task-state", changed.stepId)
        assertEquals("planning", changed.stage)
        assertEquals("уточнить цель", changed.currentStep)
        assertEquals("спросить пользователя", changed.expectedAction)
        assertFalse(changed.paused)

        // Состояние реально сохранено в хранилище
        val state = store.get("at-1")!!
        assertEquals("planning", state.stage)
        assertEquals("уточнить цель", state.currentStep)
        assertFalse(state.paused)

        // Человекочитаемая строка лога с новым состоянием
        assertTrue(events.filterIsInstance<LogEvent>().any { "Состояние задачи обновлено" in it.text })
    }

    @Test
    fun `invalid FSM transition returns tool error and model corrects itself`() {
        val store = newStore("at-invalid.db")
        val llm = FakeToolCallLlmClient(
            listOf(
                MockPlan.ToolCall("task_state", """{"stage":"planning"}"""),
                // planning → validation ЗАПРЕЩЁН — модель получает ошибку со списком разрешённых
                MockPlan.ToolCall("task_state", """{"stage":"validation"}"""),
                // Модель сама корректируется на допустимый этап
                MockPlan.ToolCall("task_state", """{"stage":"execution"}"""),
                MockPlan.Text("Перешёл к исполнению"),
            ),
        )

        val events = run(llm, store, "at-2", "Сделай задачу")

        val finished = events.filterIsInstance<ToolCallFinished>()
        assertEquals(3, finished.size, "три вызова task_state: успех, ошибка FSM, корректировка")
        assertEquals("success", finished[0].status)
        assertEquals("error", finished[1].status)
        assertTrue("Недопустимый переход" in finished[1].result)
        assertTrue("execution" in finished[1].result, "в ошибке должны быть перечислены разрешённые этапы")
        assertEquals("success", finished[2].status)

        // task_state_changed только после УСПЕШНЫХ обновлений: planning и execution
        val changed = events.filterIsInstance<TaskStateChanged>()
        assertEquals(2, changed.size)
        assertEquals("planning", changed[0].stage)
        assertEquals("execution", changed[1].stage)

        // Итоговое состояние в хранилище — исправленное
        assertEquals("execution", store.get("at-2")!!.stage)
    }

    @Test
    fun `missing stage argument returns tool error without touching store`() {
        val store = newStore("at-noargs.db")
        val llm = FakeToolCallLlmClient(
            listOf(
                MockPlan.ToolCall("task_state", """{"current_step":"шаг без этапа"}"""),
                MockPlan.Text("Уточню этап"),
            ),
        )
        val events = run(llm, store, "at-3", "Начнём")

        val toolFinished = events.filterIsInstance<ToolCallFinished>().single()
        assertEquals("error", toolFinished.status)
        assertTrue("stage" in toolFinished.result)
        assertNull(store.get("at-3"), "состояние не должно создаваться при невалидных аргументах")
        assertTrue(events.filterIsInstance<TaskStateChanged>().isEmpty())
    }

    @Test
    fun `next run injects task state system block with pause warning`() {
        val store = newStore("at-prompt.db")
        store.upsert("at-4", TaskStateStore.STAGE_EXECUTION, "шаг 3", "ждать проверки", true)

        val llm = FakeToolCallLlmClient(fallback = MockPlan.Text("Пауза подтверждена"))
        run(llm, store, "at-4", "Продолжим?")

        val prompt = llm.prompts.single()
        val block = prompt
            .filter { it.role == "system" }
            .mapNotNull { it.content }
            .firstOrNull { it.startsWith("=== СОСТОЯНИЕ ЗАДАЧИ ===") }
        assertTrue(block != null, "блок состояния должен быть в промпте")
        assertTrue("Этап: execution" in block!!)
        assertTrue("Текущий шаг: шаг 3" in block)
        assertTrue("Ожидаемое действие: ждать проверки" in block)
        assertTrue("ЗАДАЧА НА ПАУЗЕ" in block, "при паузе должна быть команда не выполнять шаги")
        assertTrue("task_state" in block, "должна быть инструкция обновлять состояние инструментом")
    }

    @Test
    fun `run without task state has no task block`() {
        val store = newStore("at-noblock.db")
        val llm = FakeToolCallLlmClient(fallback = MockPlan.Text("готово"))
        run(llm, store, "at-5", "Привет")

        val prompt = llm.prompts.single()
        assertTrue(
            prompt.none { it.role == "system" && it.content?.startsWith("=== СОСТОЯНИЕ ЗАДАЧИ ===") == true },
            "без состояния задачи блока быть не должно",
        )
    }

    @Test
    fun `tool update preserves pause flag set by user`() {
        val store = newStore("at-preserve.db")
        store.upsert("at-6", TaskStateStore.STAGE_EXECUTION, "шаг", null, true) // пауза от пользователя

        val llm = FakeToolCallLlmClient(
            listOf(
                MockPlan.ToolCall("task_state", """{"stage":"validation","current_step":"проверяю результат"}"""),
                MockPlan.Text("Обновил состояние"),
            ),
        )
        run(llm, store, "at-6", "Что дальше?")

        val state = store.get("at-6")!!
        assertEquals("validation", state.stage)
        assertTrue(state.paused, "инструмент task_state НЕ должен снимать паузу — это делает только пользователь")
        assertEquals("проверяю результат", state.currentStep)
    }

    // ---- Воркфлоу Day-14: manual pause / continue / auto ----

    @Test
    fun `manual workflow pauses at stage boundary and persists stage output`() {
        val store = newStore("aw-manual.db")
        val wf = workflowSettings(true, WorkflowSettings.MODE_MANUAL)
        val llm = FakeToolCallLlmClient(fallback = MockPlan.Text("Этап планирования: три шага"))

        val events = run(llm, store, "aw-1", "Составь план", wf)

        // После agent_finished — workflow_paused (ручной режим, этап != done)
        val paused = events.filterIsInstance<WorkflowPaused>().single()
        assertEquals("planning", paused.stage)
        assertTrue(paused.await)
        assertEquals("Этап планирования: три шага", paused.output)

        // Состояние: этап planning, результат плана сохранён, ждём подтверждения
        val state = store.get("aw-1")!!
        assertEquals("planning", state.stage)
        assertEquals("Этап планирования: три шага", state.plan)
        assertTrue(state.awaitConfirmation)
        assertFalse(state.paused)

        // task_state_changed несёт полный набор полей воркфлоу
        val changed = events.filterIsInstance<TaskStateChanged>().single()
        assertTrue(changed.awaitConfirmation)
        assertEquals("Этап планирования: три шага", changed.plan)

        // Директива воркфлоу в промпте: ручной режим, этап планирование
        val prompt = llm.prompts.single()
        val directive = prompt.filter { it.role == "system" }
            .mapNotNull { it.content }
            .firstOrNull { it.startsWith("=== ВОРКФЛОУ") }
        assertTrue(directive != null, "директива воркфлоу должна быть в промпте")
        assertTrue(directive!!.contains("вручную"))
        assertTrue(directive.contains("планирование"))
        assertTrue(directive.contains("Выполни ТОЛЬКО этот этап"))
    }

    @Test
    fun `continue run moves to next stage without a new user message`() {
        val store = newStore("aw-continue.db")
        val wf = workflowSettings(true, WorkflowSettings.MODE_MANUAL)
        // Симулируем /continue: сначала завершённый этап планирования, потом advance в execution
        store.setStageOutput("aw-2", TaskStateStore.STAGE_PLANNING, "План из прошлого этапа", await = true)
        store.advance("aw-2", TaskStateStore.STAGE_EXECUTION)
        // В историю уже записано прошлое сообщение пользователя и ответ ассистента
        val userCountBefore = sessionStore.getStored("aw-2").count { it.role == "user" }

        val llm = FakeToolCallLlmClient(fallback = MockPlan.Text("Реализация по плану"))
        val events = agent(llm, store, wf).continueRun("aw-2")
            .collectList().block(Duration.ofSeconds(10))!!

        // ContinueRun НЕ добавляет user-сообщение
        val stored = sessionStore.getStored("aw-2")
        assertEquals(userCountBefore, stored.count { it.role == "user" }, "continueRun не должен добавлять user-сообщение")

        // Промпт содержит директиву воркфлоу для этапа выполнение + прежний план
        val prompt = llm.prompts.single()
        val directive = prompt.filter { it.role == "system" }.mapNotNull { it.content }
            .firstOrNull { it.startsWith("=== ВОРКФЛОУ") }
        assertTrue(directive != null)
        assertTrue(directive!!.contains("выполнение"))
        val stateBlock = prompt.filter { it.role == "system" }.mapNotNull { it.content }
            .firstOrNull { it.startsWith("=== СОСТОЯНИЕ ЗАДАЧИ ===") }
        assertTrue(stateBlock != null, "блок состояния должен быть в промпте")
        assertTrue(stateBlock!!.contains("План (готов):\nПлан из прошлого этапа"))

        // Завершение этапа выполнения в ручном режиме — WorkflowPaused(stage=execution)
        val paused = events.filterIsInstance<WorkflowPaused>().single()
        assertEquals("execution", paused.stage)
        val state = store.get("aw-2")!!
        assertEquals("execution", state.stage)
        assertEquals("Реализация по плану", state.implementation)
        assertEquals("План из прошлого этапа", state.plan, "план сохраняется при переходе")
        assertTrue(state.awaitConfirmation)
    }

    @Test
    fun `auto workflow does not pause and agent walks all stages`() {
        val store = newStore("aw-auto.db")
        val wf = workflowSettings(true, WorkflowSettings.MODE_AUTO)
        // Авто: агент сам проходит этапы через инструмент task_state и завершает на done
        val llm = FakeToolCallLlmClient(
            listOf(
                MockPlan.ToolCall("task_state", """{"stage":"execution","current_step":"реализую план"}"""),
                MockPlan.ToolCall("task_state", """{"stage":"validation","current_step":"проверяю результат"}"""),
                MockPlan.ToolCall("task_state", """{"stage":"done"}"""),
                MockPlan.Text("Задача выполнена"),
            ),
        )

        val events = run(llm, store, "aw-3", "Сделай задачу", wf)

        // В авто-режиме WorkflowPaused НЕ эмитится
        assertTrue(events.filterIsInstance<WorkflowPaused>().isEmpty(), "в авто-режиме паузы быть не должно")
        val state = store.get("aw-3")!!
        assertEquals("done", state.stage)
        assertFalse(state.awaitConfirmation)

        // Директива воркфлоу — авторежим
        val directive = llm.prompts.first()
            .filter { it.role == "system" }.mapNotNull { it.content }
            .firstOrNull { it.startsWith("=== ВОРКФЛОУ") }
        assertTrue(directive != null)
        assertTrue(directive!!.contains("Проходи все этапы подряд"))
    }

    @Test
    fun `workflow directive requires concrete plan on planning`() {
        val store = newStore("aw-concrete-plan.db")
        val wf = workflowSettings(true, WorkflowSettings.MODE_AUTO)
        val llm = FakeToolCallLlmClient(
            listOf(
                MockPlan.ToolCall("task_state", """{"stage":"execution","current_step":"реализую"}"""),
                MockPlan.Text("Задача выполнена"),
            ),
        )
        run(llm, store, "aw-cp", "Сделай задачу", wf)

        // Директива воркфлоу на планировании требует КОНКРЕТНЫЙ план, а не общую фразу.
        val prompt = llm.prompts.first()
        val directive = prompt.filter { it.role == "system" }.mapNotNull { it.content }
            .firstOrNull { it.startsWith("=== ВОРКФЛОУ") }
        assertTrue(directive != null)
        assertTrue(directive!!.contains("КОНКРЕТНЫЙ"), "план должен быть КОНКРЕТНЫМ")
        assertTrue(directive.contains("шаги"), "план должен содержать шаги")
        assertTrue(directive.contains("файлы"), "план должен содержать файлы/функции")
        assertTrue(directive.contains("крайние случаи", ignoreCase = true), "план должен указывать крайние случаи")
        assertFalse(directive.dropWhile { it != '\n' }.contains("Планирую реализовать"))
    }

    @Test
    fun `auto workflow persists stage narration as separate assistant messages`() {
        val store = newStore("aw-auto-msgs.db")
        val wf = workflowSettings(true, WorkflowSettings.MODE_AUTO)
        // Модель на каждом этапе даёт повествование (ContentDelta) и в том же ответе
        // переходит дальше инструментом task_state — как в реальном игре с моделью.
        val script = listOf(
            listOf(
                LlmEvent.ContentDelta("План: три шага"),
                LlmEvent.ToolCallsComplete(listOf(LlmToolCall(0, "c1", "task_state", """{"stage":"execution"}"""))),
                LlmEvent.Finished("tool_calls"),
            ),
            listOf(
                LlmEvent.ContentDelta("Реализация: сделал по плану"),
                LlmEvent.ToolCallsComplete(listOf(LlmToolCall(0, "c2", "task_state", """{"stage":"validation"}"""))),
                LlmEvent.Finished("tool_calls"),
            ),
            listOf(
                LlmEvent.ContentDelta("Проверка: всё работает"),
                LlmEvent.ToolCallsComplete(listOf(LlmToolCall(0, "c3", "task_state", """{"stage":"done"}"""))),
                LlmEvent.Finished("tool_calls"),
            ),
            listOf(LlmEvent.ContentDelta("Задача выполнена"), LlmEvent.Finished("stop")),
        )
        val idx = AtomicInteger(0)
        val llm = object : LlmClient {
            override fun streamChat(
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                settings: LlmSettings,
            ): Flux<LlmEvent> =
                Flux.fromIterable(script[minOf(idx.getAndIncrement(), script.size - 1)])
        }

        run(llm, store, "aw-auto-2", "Сделай задачу", wf)

        // В истории — отдельные ответы на каждом этапе + финальный, а не только последний.
        val assistant = sessionStore.get("aw-auto-2").filter { it.role == "assistant" }.map { it.content }
        assertEquals(
            listOf("План: три шага", "Реализация: сделал по плану", "Проверка: всё работает", "Задача выполнена"),
            assistant,
        )
        // Результаты этапов сохранились в состояние задачи (зеркально ручному режиму).
        val state = store.get("aw-auto-2")!!
        assertEquals("План: три шага", state.plan)
        assertEquals("Реализация: сделал по плану", state.implementation)
        assertEquals("Проверка: всё работает", state.validation)
        assertFalse(state.awaitConfirmation)
    }

    @Test
    fun `auto workflow emits workflow_stage_finished for each committed stage`() {
        val store = newStore("aw-stage-finished.db")
        val wf = workflowSettings(true, WorkflowSettings.MODE_AUTO)
        // Модель на каждом этапе даёт повествование и в том же ответе переходит дальше
        // инструментом task_state — как в реальном игре с моделью.
        val script = listOf(
            listOf(
                LlmEvent.ContentDelta("План: три шага"),
                LlmEvent.ToolCallsComplete(listOf(LlmToolCall(0, "c1", "task_state", """{"stage":"execution"}"""))),
                LlmEvent.Finished("tool_calls"),
            ),
            listOf(
                LlmEvent.ContentDelta("Реализация: сделал по плану"),
                LlmEvent.ToolCallsComplete(listOf(LlmToolCall(0, "c2", "task_state", """{"stage":"validation"}"""))),
                LlmEvent.Finished("tool_calls"),
            ),
            listOf(
                LlmEvent.ContentDelta("Проверка: всё работает"),
                LlmEvent.ToolCallsComplete(listOf(LlmToolCall(0, "c3", "task_state", """{"stage":"done"}"""))),
                LlmEvent.Finished("tool_calls"),
            ),
            listOf(LlmEvent.ContentDelta("Задача выполнена"), LlmEvent.Finished("stop")),
        )
        val idx = AtomicInteger(0)
        val llm = object : LlmClient {
            override fun streamChat(
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                settings: LlmSettings,
            ): Flux<LlmEvent> =
                Flux.fromIterable(script[minOf(idx.getAndIncrement(), script.size - 1)])
        }

        val events = run(llm, store, "aw-sf", "Сделай задачу", wf)

        // Для КАЖДОГО завершённого этапа (planning/execution/validation) бэкенд эмитит
        // workflow_stage_finished — надёжная граница этапа для разбиения пузыря на фронте.
        val stageFinished = events.filterIsInstance<WorkflowStageFinished>()
        assertEquals(3, stageFinished.size, "три завершённых этапа: planning, execution, validation")
        assertEquals(listOf("planning", "execution", "validation"), stageFinished.map { it.stage })
        assertEquals("План: три шага", stageFinished[0].output)
        assertEquals("Реализация: сделал по плану", stageFinished[1].output)
        assertEquals("Проверка: всё работает", stageFinished[2].output)
        assertTrue(events.filterIsInstance<WorkflowStageFinished>().none { it.stage == "done" }, "для done событие не эмитится")
    }

    @Test
    fun `auto workflow stops immediately when task is paused`() {
        val store = newStore("aw-auto-pause.db")
        val wf = workflowSettings(true, WorkflowSettings.MODE_AUTO)
        // Пользователь уже поставил паузу (REST PUT task-state {paused:true}).
        store.upsert("aw-p1", TaskStateStore.STAGE_EXECUTION, "шаг", null, true)
        val llm = FakeToolCallLlmClient(fallback = MockPlan.Text("не должен вызываться"))

        val events = run(llm, store, "aw-p1", "Что дальше?", wf)

        // При паузе модель НЕ вызывается — выполнение уважает внешнюю паузу.
        assertEquals(0, llm.callCount.get(), "пауза должна останавливать выполнение до вызова LLM")
        assertTrue(events.filterIsInstance<TaskStateChanged>().any { it.paused }, "панели сообщаем paused=true")
        assertTrue(store.get("aw-p1")!!.paused, "пауза сохраняется")
    }

    @Test
    fun `auto pauses mid-generation stays on current stage and does not advance`() {
        val store = newStore("aw-pause-mid.db")
        val wf = workflowSettings(true, WorkflowSettings.MODE_AUTO)
        // Агент проходит планирование → выполнение; на ВЫПОЛНЕНИИ пользователь ставит паузу,
        // пока модель генерирует ответ модели, и модель в том же ответе переходит к проверке.
        // Пауза легла в БД после проверки в топе цикла, поэтому остановить должен
        // повторный check (см. AgentImpl: после LLM-ответа, до обработки task_state).
        val script = listOf(
            listOf(
                LlmEvent.ContentDelta("План: три шага"),
                LlmEvent.ToolCallsComplete(listOf(LlmToolCall(0, "c1", "task_state", """{"stage":"execution"}"""))),
                LlmEvent.Finished("tool_calls"),
            ),
            listOf(
                LlmEvent.ContentDelta("Реализация: сделал по плану"),
                LlmEvent.ToolCallsComplete(listOf(LlmToolCall(0, "c2", "task_state", """{"stage":"validation"}"""))),
                LlmEvent.Finished("tool_calls"),
            ),
            listOf(LlmEvent.ContentDelta("Проверка: всё работает"), LlmEvent.Finished("stop")),
        )
        val idx = AtomicInteger(0)
        val llm = object : LlmClient {
            override fun streamChat(
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                settings: LlmSettings,
            ): Flux<LlmEvent> {
                val call = idx.getAndIncrement()
                // Пользователь ставит паузу, ПОКА модель генерирует ответ на этап выполнения.
                if (call == 1) store.setPaused("aw-mid", true)
                return Flux.fromIterable(script[minOf(call, script.size - 1)])
            }
        }

        val events = run(llm, store, "aw-mid", "Сделай задачу", wf)

        // Агент остановился на ВЫПОЛНЕНИИ: этап execution, пауза осталась.
        val state = store.get("aw-mid")!!
        assertEquals("execution", state.stage)
        assertTrue(state.paused, "пауза сохраняется")

        // Переход в validation НЕ произошёл, результат выполнения не сохранён.
        assertTrue(
            events.filterIsInstance<TaskStateChanged>().none { it.stage == TaskStateStore.STAGE_VALIDATION },
            "не должно быть перехода в validation",
        )
        assertNull(state.implementation, "результат выполнения не фиксируется в implementation")
        assertTrue(events.any { it is AgentFinished }, "пузырь закрывается agent_finished на текущем этапе")
    }

    @Test
    fun `workflow disabled behaves like before without pause`() {
        val store = newStore("aw-off.db")
        val wf = workflowSettings(false, WorkflowSettings.MODE_MANUAL)
        val llm = FakeToolCallLlmClient(fallback = MockPlan.Text("Обычный ответ"))
        val events = run(llm, store, "aw-4", "Привет", wf)

        assertTrue(events.filterIsInstance<WorkflowPaused>().isEmpty())
        assertNull(store.get("aw-4"), "при выключенном воркфлоу состояние задачи не создаётся")
    }
}
