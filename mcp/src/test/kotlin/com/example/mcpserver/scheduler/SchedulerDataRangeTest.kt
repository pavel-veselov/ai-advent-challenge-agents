package com.example.mcpserver.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Тесты scheduler_data_range (Day-18): отдача сырых запусков из task_runs за окно,
 * уважение since_hours, фильтрация по taskId и пустые данные.
 */
class SchedulerDataRangeTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun tools(): Pair<SchedulerTools, SchedulerStore> {
        val store = SchedulerStore(SqliteTestSupport.jdbc(tmpDir.resolve("range-${UUID.randomUUID()}.db")))
        val engine = SchedulerEngine(store, FakeSourceCollector(), ObjectMapper())
        val tools = SchedulerTools(store, engine, ObjectMapper())
        return tools to store
    }

    @Suppress("UNCHECKED_CAST")
    private fun runsOf(map: Map<String, Any?>): List<Map<String, Any?>> =
        map["runs"] as List<Map<String, Any?>>

    @Test
    fun `returns runs within window with camelCase fields`() {
        val (tools, store) = tools()
        val task = store.addTask("погода", "weather", 60, null)!!
        store.recordRun(task.id, "weather", "SUCCESS", """{"temperature":10.0}""", null)

        val res = tools.dataRange(task.id, 24).block()!!

        assertEquals(1, res["count"] as Int)
        assertEquals(24, res["since"] as Int)
        assertEquals(task.id, res["taskId"] as Long?)
        val run = runsOf(res)[0]
        assertEquals(task.id, run["taskId"] as Long?)
        assertEquals("weather", run["source"])
        assertEquals("SUCCESS", run["status"])
        assertEquals(task.id, run["id"] as Long?)
        assertTrue((run["startedAt"] as String).isNotBlank())
        // finished_at в схеме нет, поэтому null.
        assertNull(run["finishedAt"])
        @Suppress("UNCHECKED_CAST")
        val parsed = run["resultJson"] as Map<String, Any?>
        assertEquals(10.0, (parsed["temperature"] as Number).toDouble())
    }

    @Test
    fun `respects sinceHours window and parses rates and titles`() {
        val (tools, store) = tools()
        val cur = store.addTask("валюты", "currency", 60, null)!!
        store.recordRun(
            cur.id, "currency", "SUCCESS",
            """{"base":"RUB","rates":{"USD":{"rate":90.0,"change_from_previous":0.1,"nominal":1,"name":"Доллар США"}}}""",
            null,
        )
        val news = store.addTask("новости", "news", 60, null)!!
        store.recordRun(news.id, "news", "ERROR", null, "boom")

        val res = tools.dataRange(null, 24).block()!!
        assertEquals(2, res["count"] as Int)
        val runs = runsOf(res)
        assertEquals("currency", runs[0]["source"])
        @Suppress("UNCHECKED_CAST")
        val rates = (runs[0]["resultJson"] as Map<String, Any?>)["rates"] as Map<String, Any?>
        assertEquals(90.0, (rates["USD"] as Map<String, Any?>)["rate"] as Double)
        assertEquals("ERROR", runs[1]["status"])
        assertEquals("boom", runs[1]["error"])
        assertNull(runs[1]["resultJson"])
    }

    @Test
    fun `filters by taskId when given`() {
        val (tools, store) = tools()
        val a = store.addTask("a", "weather", 60, null)!!
        val b = store.addTask("b", "news", 60, null)!!
        store.recordRun(a.id, "weather", "SUCCESS", """{"temperature":1.0}""", null)
        store.recordRun(a.id, "weather", "SUCCESS", """{"temperature":2.0}""", null)
        store.recordRun(b.id, "news", "SUCCESS", """[{"title":"X"}]""", null)

        val res = tools.dataRange(a.id, 24).block()!!
        assertEquals(2, res["count"] as Int)
        assertEquals(a.id, res["taskId"] as Long?)
        assertTrue(runsOf(res).all { it["taskId"] as Long == a.id })
    }

    @Test
    fun `handles empty data when no runs in window`() {
        val (tools, store) = tools()
        val task = store.addTask("погода", "weather", 60, null)!!

        val res = tools.dataRange(task.id, 24).block()!!

        assertEquals(0, res["count"] as Int)
        assertEquals(24, res["since"] as Int)
        assertTrue(runsOf(res).isEmpty())
    }
}
