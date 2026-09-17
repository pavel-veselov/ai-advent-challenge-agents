package com.example.llmagent.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Юнит-тесты TaskStateStore (Day-13, FSM task_state): создание/обновление состояния,
 * флаг паузы (setPaused не трогает этап/шаги), обрезка длинного текста, удаление,
 * переживание «перезапуска» хранилища и ПРАВИЛА переходов конечного автомата
 * (единый источник истины — companion объекта). SQLite-файл временный: рабочая
 * БД ./data не трогается.
 */
class TaskStateStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun newStore(name: String): TaskStateStore =
        TaskStateStore(SqliteTestSupport.jdbc(tmpDir.resolve(name)))

    @Test
    fun `get returns null for session without task`() {
        val store = newStore("ts-empty.db")
        assertNull(store.get("nope"))
    }

    @Test
    fun `upsert creates state and get returns it`() {
        val store = newStore("ts-create.db")
        val saved = store.upsert("s1", TaskStateStore.STAGE_PLANNING, "уточнить цель", "спросить пользователя", false)
        assertTrue(saved != null, "upsert должен вернуть сохранённую строку")
        val loaded = store.get("s1")!!
        assertEquals("s1", loaded.sessionId)
        assertEquals(TaskStateStore.STAGE_PLANNING, loaded.stage)
        assertEquals("уточнить цель", loaded.currentStep)
        assertEquals("спросить пользователя", loaded.expectedAction)
        assertFalse(loaded.paused, "новая строка создаётся без паузы")
        assertTrue(loaded.updatedAt.isNotBlank())
    }

    @Test
    fun `upsert same stage updates step and action`() {
        val store = newStore("ts-update.db")
        store.upsert("s2", TaskStateStore.STAGE_PLANNING, "шаг 1", null, false)
        store.upsert("s2", TaskStateStore.STAGE_PLANNING, "шаг 2", "запланировать", false)
        val state = store.get("s2")!!
        assertEquals(TaskStateStore.STAGE_PLANNING, state.stage)
        assertEquals("шаг 2", state.currentStep)
        assertEquals("запланировать", state.expectedAction)
    }

    @Test
    fun `upsert trims long text to TEXT_MAX_LENGTH`() {
        val store = newStore("ts-trim.db")
        val long = "х".repeat(3000)
        store.upsert("s3", TaskStateStore.STAGE_EXECUTION, long, long, false)
        val state = store.get("s3")!!
        assertEquals(TaskStateStore.TEXT_MAX_LENGTH, state.currentStep!!.length)
        assertEquals(TaskStateStore.TEXT_MAX_LENGTH, state.expectedAction!!.length)
    }

    @Test
    fun `setPaused changes only paused flag`() {
        val store = newStore("ts-pause.db")
        store.upsert("s4", TaskStateStore.STAGE_EXECUTION, "шаг", "действие", false)
        val paused = store.setPaused("s4", true)!!
        assertTrue(paused.paused)
        assertEquals(TaskStateStore.STAGE_EXECUTION, paused.stage, "этап не должен измениться")
        assertEquals("шаг", paused.currentStep, "шаг не должен измениться")
        assertEquals("действие", paused.expectedAction, "ожидаемое действие не должно измениться")
        assertTrue(store.get("s4")!!.paused)
        // продолжение
        assertFalse(store.setPaused("s4", false)!!.paused)
    }

    @Test
    fun `setPaused returns null for session without task`() {
        val store = newStore("ts-paused-missing.db")
        assertNull(store.setPaused("ghost", true))
    }

    @Test
    fun `remove deletes row and reports existence`() {
        val store = newStore("ts-remove.db")
        store.upsert("s5", TaskStateStore.STAGE_DONE, null, null, false)
        assertTrue(store.remove("s5"), "первый remove должен найти строку")
        assertNull(store.get("s5"))
        assertFalse(store.remove("s5"), "второй remove — строки уже нет")
    }

    @Test
    fun `state survives store restart on same db file`() {
        val file = tmpDir.resolve("ts-restart.db")
        val first = TaskStateStore(SqliteTestSupport.jdbc(file))
        first.upsert("s6", TaskStateStore.STAGE_VALIDATION, "проверяю", "сравнить", true)
        // «Перезапуск» хранилища: новый экземпляр поверх того же файла БД
        val second = TaskStateStore(SqliteTestSupport.jdbc(file))
        val state = second.get("s6")!!
        assertEquals(TaskStateStore.STAGE_VALIDATION, state.stage)
        assertEquals("проверяю", state.currentStep)
        assertEquals("сравнить", state.expectedAction)
        assertTrue(state.paused)
    }

    @Test
    fun `isValidStage accepts only FSM stages`() {
        for (s in TaskStateStore.STAGES) assertTrue(TaskStateStore.isValidStage(s), "этап $s должен быть валиден")
        assertFalse(TaskStateStore.isValidStage("invalid"))
        assertFalse(TaskStateStore.isValidStage(""))
    }

    @Test
    fun `canTransition follows the FSM graph and allows same-stage updates`() {
        // Разрешённые переходы по графу FSM
        assertTrue(TaskStateStore.canTransition("planning", "execution"))
        assertTrue(TaskStateStore.canTransition("planning", "done"))
        assertTrue(TaskStateStore.canTransition("execution", "validation"))
        assertTrue(TaskStateStore.canTransition("execution", "planning"))
        assertTrue(TaskStateStore.canTransition("validation", "done"))
        assertTrue(TaskStateStore.canTransition("validation", "execution"))
        assertTrue(TaskStateStore.canTransition("done", "planning"))
        assertTrue(TaskStateStore.canTransition("done", "execution"))
        // Тот же этап — обновлять можно всегда
        for (s in TaskStateStore.STAGES) assertTrue(TaskStateStore.canTransition(s, s))
        // Запрещённые переходы
        assertFalse(TaskStateStore.canTransition("planning", "validation"))
        assertFalse(TaskStateStore.canTransition("execution", "done"))
        assertFalse(TaskStateStore.canTransition("validation", "planning"))
        assertFalse(TaskStateStore.canTransition("done", "validation"))
    }

    @Test
    fun `allowedTargets lists only other reachable stages`() {
        assertEquals(setOf("execution", "done"), TaskStateStore.allowedTargets("planning"))
        assertEquals(setOf("validation", "planning"), TaskStateStore.allowedTargets("execution"))
        assertEquals(setOf("done", "execution"), TaskStateStore.allowedTargets("validation"))
        assertEquals(setOf("planning", "execution"), TaskStateStore.allowedTargets("done"))
        assertTrue(TaskStateStore.allowedTargets("invalid").isEmpty())
    }

    // ---- Воркфлоу Day-14: plan/implementation/validation/awaitConfirmation ----

    @Test
    fun `upsert persists workflow fields`() {
        val store = newStore("ts-wf-create.db")
        val saved = store.upsert(
            "w1", TaskStateStore.STAGE_PLANNING, null, null, false,
            plan = "план из планирования", awaitConfirmation = true,
        )
        assertTrue(saved != null)
        assertEquals("план из планирования", saved!!.plan)
        assertEquals(null, saved.implementation)
        assertEquals(null, saved.validation)
        assertTrue(saved.awaitConfirmation)

        val loaded = store.get("w1")!!
        assertEquals("план из планирования", loaded.plan)
        assertTrue(loaded.awaitConfirmation)
    }

    @Test
    fun `setStageOutput writes to the column for the stage and awaits`() {
        val store = newStore("ts-wf-output.db")
        // планирование
        val a = store.setStageOutput("w2", TaskStateStore.STAGE_PLANNING, "план", await = true)!!
        assertEquals(TaskStateStore.STAGE_PLANNING, a.stage)
        assertEquals("план", a.plan)
        assertTrue(a.awaitConfirmation)
        // выполнение: plan сохраняется, implementation записывается
        val b = store.setStageOutput("w2", TaskStateStore.STAGE_EXECUTION, "реализация", await = true)!!
        assertEquals("план", b.plan, "план предыдущего этапа не должен стираться")
        assertEquals("реализация", b.implementation)
        assertTrue(b.awaitConfirmation)
        // проверка
        val c = store.setStageOutput("w2", TaskStateStore.STAGE_VALIDATION, "вердикт", await = true)!!
        assertEquals("вердикт", c.validation)
        assertEquals("реализация", c.implementation)
        assertTrue(c.awaitConfirmation)
    }

    @Test
    fun `setStageOutput creates state from scratch for first stage`() {
        val store = newStore("ts-wf-first.db")
        val saved = store.setStageOutput("w3", TaskStateStore.STAGE_PLANNING, "первый план")!!
        assertEquals(TaskStateStore.STAGE_PLANNING, saved.stage)
        assertEquals("первый план", saved.plan)
        assertFalse(saved.paused, "новая строка создаётся без паузы")
        assertTrue(saved.awaitConfirmation, "по умолчанию — ждёт подтверждения")
    }

    @Test
    fun `setStageOutput rejects unknown stage`() {
        val store = newStore("ts-wf-badstage.db")
        assertNull(store.setStageOutput("w4", "bogus", "текст"))
    }

    @Test
    fun `advance moves to next stage and clears await`() {
        val store = newStore("ts-wf-advance.db")
        store.setStageOutput("w5", TaskStateStore.STAGE_PLANNING, "план", await = true)
        val next = store.advance("w5", TaskStateStore.STAGE_EXECUTION)!!
        assertEquals(TaskStateStore.STAGE_EXECUTION, next.stage)
        assertEquals("план", next.plan, "план сохраняется при переходе")
        assertFalse(next.awaitConfirmation, "подтверждение получено — ожидание сброшено")
        assertFalse(next.paused, "переход снимает паузу")
    }

    @Test
    fun `advance returns null when state missing`() {
        val store = newStore("ts-wf-advance-missing.db")
        assertNull(store.advance("ghost", TaskStateStore.STAGE_EXECUTION))
    }

    @Test
    fun `clearAwait resets await and keeps stage and pause`() {
        val store = newStore("ts-wf-clear.db")
        store.setStageOutput("w6", TaskStateStore.STAGE_PLANNING, "план", await = true)
        store.setPaused("w6", true)
        val cleared = store.clearAwait("w6")!!
        assertFalse(cleared.awaitConfirmation)
        assertEquals(TaskStateStore.STAGE_PLANNING, cleared.stage)
        assertTrue(cleared.paused, "пауза сохраняется при сбросе ожидания")
    }

    @Test
    fun `setPaused preserves workflow fields`() {
        val store = newStore("ts-wf-pause.db")
        store.setStageOutput("w7", TaskStateStore.STAGE_PLANNING, "план", await = true)
        val paused = store.setPaused("w7", true)!!
        assertTrue(paused.paused)
        assertEquals("план", paused.plan)
        assertTrue(paused.awaitConfirmation, "пауза не должна трогать флаг ожидания")
    }

    @Test
    fun `workflow fields survive store restart on same db file`() {
        val file = tmpDir.resolve("ts-wf-restart.db")
        val first = TaskStateStore(SqliteTestSupport.jdbc(file))
        first.setStageOutput("w8", TaskStateStore.STAGE_VALIDATION, "вердикт", await = true)
        val second = TaskStateStore(SqliteTestSupport.jdbc(file))
        val state = second.get("w8")!!
        assertEquals("вердикт", state.validation)
        assertTrue(state.awaitConfirmation)
    }

    @Test
    fun `nextWorkflowStage follows the linear flow`() {
        assertEquals(TaskStateStore.STAGE_EXECUTION, TaskStateStore.nextWorkflowStage(TaskStateStore.STAGE_PLANNING))
        assertEquals(TaskStateStore.STAGE_VALIDATION, TaskStateStore.nextWorkflowStage(TaskStateStore.STAGE_EXECUTION))
        assertEquals(TaskStateStore.STAGE_DONE, TaskStateStore.nextWorkflowStage(TaskStateStore.STAGE_VALIDATION))
        assertNull(TaskStateStore.nextWorkflowStage(TaskStateStore.STAGE_DONE))
        assertNull(TaskStateStore.nextWorkflowStage("bogus"))
    }

    @Test
    fun `stageOutputColumn maps stages to their columns`() {
        assertEquals("plan", TaskStateStore.stageOutputColumn(TaskStateStore.STAGE_PLANNING))
        assertEquals("implementation", TaskStateStore.stageOutputColumn(TaskStateStore.STAGE_EXECUTION))
        assertEquals("validation", TaskStateStore.stageOutputColumn(TaskStateStore.STAGE_VALIDATION))
        assertNull(TaskStateStore.stageOutputColumn(TaskStateStore.STAGE_DONE))
    }
}
