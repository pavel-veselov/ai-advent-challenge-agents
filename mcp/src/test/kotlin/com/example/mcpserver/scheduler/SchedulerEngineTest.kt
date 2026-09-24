package com.example.mcpserver.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты планировщика (Day-18) на реальном SQLite-хранилище + фейковом источнике:
 * добавление задачи записывает запуски, смена интервала пере-расписывает (поток
 * останавливается), удаление отменяет, ошибка источника фиксируется как ERROR.
 * Интервал в тестах берём 1с — минимальный валидный период (>=5) проверяется на уровне MCP-инструмента.
 */
class SchedulerEngineTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun setup(): Triple<SchedulerEngine, SchedulerStore, FakeSourceCollector> {
        val store = SchedulerStore(SqliteTestSupport.jdbc(tmpDir.resolve("engine-${UUID.randomUUID()}.db")))
        val collector = FakeSourceCollector()
        val engine = SchedulerEngine(store, collector, ObjectMapper())
        return Triple(engine, store, collector)
    }

    @Test
    fun `addTask schedules run and records task_runs`() {
        val (engine, store, collector) = setup()
        try {
            val task = engine.addTask("погода", "weather", 1, """{"city":"Москва"}""")
            assertNotNull(task, "задача создана")
            Thread.sleep(2300)
            assertTrue(store.countRuns(task!!.id) >= 1, "по крайней мере один запуск записан")
            assertTrue(collector.collectCount >= 1, "источник вызывался")
            assertEquals(1, store.listTasks().size)
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `updateInterval reschedules and stops the flow`() {
        val (engine, store, _) = setup()
        try {
            val task = engine.addTask("новости", "news", 1, null)!!
            Thread.sleep(2300)
            val before = store.countRuns(task.id)
            assertTrue(before >= 1, "были запуски")

            // Сдвигаем интервал на 1000с — поток должен прекратиться.
            engine.updateInterval(task.id, 1000)
            Thread.sleep(1500)
            assertEquals(before, store.countRuns(task.id), "после смены интервала новых запусков нет")
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `removeTask cancels the flow`() {
        val (engine, store, _) = setup()
        try {
            val task = engine.addTask("валюты", "currency", 1, null)!!
            Thread.sleep(2300)
            val before = store.countRuns(task.id)
            assertTrue(before >= 1, "были запуски")

            assertTrue(engine.removeTask(task.id), "удаление успешно")
            Thread.sleep(1500)
            assertEquals(before, store.countRuns(task.id), "после удаления новых запусков нет")
            assertEquals(0, store.listTasks().size)
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `runTask records ERROR when source fails`() {
        val (engine, store, collector) = setup()
        try {
            val task = store.addTask("погода", "weather", 1000, null)!!
            collector.throwOnCollect = true

            engine.runTask(task)

            val runs = store.listRuns(task.id, 24)
            assertEquals(1, runs.size)
            assertEquals("ERROR", runs[0].status)
            assertNotNull(runs[0].error, "ошибка записана")
        } finally {
            engine.shutdown()
        }
    }
}
