package com.example.mcpserver.scheduler

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты SQLite-хранилища планировщика (Day-18): создание схемы, добавление задачи,
 * смена интервала, включение/отключение, удаление, запись запусков с выборкой по окну,
 * персистентность поверх того же файла БД.
 */
class SchedulerStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): SchedulerStore =
        SchedulerStore(SqliteTestSupport.jdbc(tmpDir.resolve("sched-${UUID.randomUUID()}.db")))

    @Test
    fun `list is empty for new store`() {
        assertTrue(store().listTasks().isEmpty())
    }

    @Test
    fun `add creates active task and list reflects it`() {
        val f = store()
        val created = f.addTask("погода москва", "weather", 60, """{"city":"Москва"}""")
        assertNotNull(created, "add возвращает созданную задачу")
        assertTrue(created!!.id > 0, "записи назначен реальный id из БД")
        assertEquals("погода москва", created.name)
        assertEquals("weather", created.source)
        assertEquals(60, created.intervalSeconds)
        assertEquals("""{"city":"Москва"}""", created.paramsJson)
        assertTrue(created.active, "новая задача активна (active=1)")

        val list = f.listTasks()
        assertEquals(1, list.size)
        assertEquals(created.id, list[0].id)
        assertTrue(list[0].active)
    }

    @Test
    fun `add rejects blank name or source and interval below 1`() {
        val f = store()
        assertNull(f.addTask("  ", "weather", 60, null), "blank имя — null")
        assertNull(f.addTask("имя", " ", 60, null), "blank source — null")
        assertNull(f.addTask("имя", "weather", 0, null), "interval < 1 — null")
        assertEquals(0, f.listTasks().size)
    }

    @Test
    fun `updateInterval changes interval and findTask returns it`() {
        val f = store()
        val created = f.addTask("новости", "news", 60, null)!!
        val updated = f.updateInterval(created.id, 120)
        assertNotNull(updated)
        assertEquals(120, updated!!.intervalSeconds)
        assertEquals(created.id, f.findTask(created.id)?.id)
        assertNull(f.updateInterval(999999, 60), "нет такой задачи — null")
    }

    @Test
    fun `setActive toggles flag`() {
        val f = store()
        val created = f.addTask("валюты", "currency", 60, null)!!
        val off = f.setActive(created.id, false)
        assertNotNull(off)
        assertTrue(!off!!.active, "после отключения active=false")
        assertEquals(0, f.listActiveTasks().size, "неактивная задача не попадает в активные")
        val on = f.setActive(created.id, true)
        assertTrue(on!!.active)
        assertEquals(1, f.listActiveTasks().size)
    }

    @Test
    fun `deleteTask returns true then false on second delete`() {
        val f = store()
        val created = f.addTask("погода", "weather", 60, null)!!
        assertTrue(f.deleteTask(created.id), "первое удаление успешно")
        assertFalse(f.deleteTask(created.id), "повторное удаление — false")
        assertTrue(f.listTasks().isEmpty())
        assertNull(f.findTask(created.id))
    }

    @Test
    fun `recordRun inserts run updates lastRunAt and listRuns filters window`() {
        val f = store()
        val created = f.addTask("погода", "weather", 60, null)!!
        assertNull(created.lastRunAt, "до первого запуска last_run_at = null")

        val run1 = f.recordRun(created.id, "weather", "SUCCESS", """{"temperature":10}""", null)
        val run2 = f.recordRun(created.id, "weather", "ERROR", null, "boom")
        assertNotNull(run1)
        assertNotNull(run2)

        val runs = f.listRuns(created.id, 24)
        assertEquals(2, runs.size, "обе записи попадают в 24-часовое окно")
        assertEquals("SUCCESS", runs[0].status)
        assertEquals("""{"temperature":10}""", runs[0].resultJson)
        assertEquals("ERROR", runs[1].status)
        assertEquals("boom", runs[1].error)

        assertEquals(2, f.countRuns(created.id), "счётчик запусков")

        val taskAfter = f.findTask(created.id)!!
        assertNotNull(taskAfter.lastRunAt, "last_run_at пере-записан после запуска")
    }

    @Test
    fun `listRuns filters by taskId and window`() {
        val f = store()
        val a = f.addTask("a", "weather", 60, null)!!
        val b = f.addTask("b", "news", 60, null)!!
        f.recordRun(a.id, "weather", "SUCCESS", """{"temperature":10}""", null)
        f.recordRun(a.id, "weather", "SUCCESS", """{"temperature":11}""", null)
        f.recordRun(b.id, "news", "SUCCESS", """[{"title":"X"}]""", null)

        assertEquals(2, f.listRuns(a.id, 24).size, "только запуски задачи a")
        assertEquals(3, f.listRuns(null, 24).size, "без фильтра — все запуски")
        assertEquals(0, f.listRuns(null, 0).size, "окно 0 часов — пусто (порог = сейчас)")
    }

    @Test
    fun `tasks persist across store restart on the same db file`() {
        val dbFile = tmpDir.resolve("sched-persist-${UUID.randomUUID()}.db")
        val first = SchedulerStore(SqliteTestSupport.jdbc(dbFile))
        val created = first.addTask("погода", "weather", 60, """{"city":"Москва"}""")!!
        first.recordRun(created.id, "weather", "SUCCESS", """{"temperature":12.5}""", null)

        val second = SchedulerStore(SqliteTestSupport.jdbc(dbFile))
        val reread = second.findTask(created.id)
        assertNotNull(reread, "задача переживает перезапуск хранилища")
        assertEquals("погода", reread!!.name)
        assertEquals(1, second.listTasks().size)
        assertEquals(1, second.countRuns(created.id))
    }
}
