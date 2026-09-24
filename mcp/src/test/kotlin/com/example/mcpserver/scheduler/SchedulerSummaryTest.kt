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
 * Тесты агрегации scheduler_summary (Day-18): в task_runs кладём фиктивные строки и
 * проверяем математику min/max/avg температуры, список курсов, заголовки и generic-сводку.
 */
class SchedulerSummaryTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun tools(): Pair<SchedulerTools, SchedulerStore> {
        val store = SchedulerStore(SqliteTestSupport.jdbc(tmpDir.resolve("sum-${UUID.randomUUID()}.db")))
        val engine = SchedulerEngine(store, FakeSourceCollector(), ObjectMapper())
        val tools = SchedulerTools(store, engine, ObjectMapper())
        return tools to store
    }

    @Test
    fun `weather summary computes min max avg latest`() {
        val (tools, store) = tools()
        val task = store.addTask("погода", "weather", 60, null)!!
        store.recordRun(task.id, "weather", "SUCCESS", """{"temperature":10.0}""", null)
        store.recordRun(task.id, "weather", "SUCCESS", """{"temperature":20.0}""", null)
        store.recordRun(task.id, "weather", "SUCCESS", """{"temperature":30.0}""", null)

        val s = tools.summary(task.id, 24).block()!!

        assertEquals(task.id, s["taskId"] as Long?)
        assertEquals(3, s["count"] as Int)
        assertEquals(24, s["since"] as Int)
        assertEquals(10.0, s["min"] as Double)
        assertEquals(30.0, s["max"] as Double)
        assertEquals(20.0, s["avg"] as Double)
        assertEquals(30.0, s["latest"] as Double)
    }

    @Test
    fun `currency summary returns latest result and sorted rates list`() {
        val (tools, store) = tools()
        val task = store.addTask("валюты", "currency", 60, null)!!
        store.recordRun(
            task.id, "currency", "SUCCESS",
            """{"base":"RUB","rates":{"USD":{"rate":90.0,"change_from_previous":0.1,"nominal":1,"name":"Доллар США"},"EUR":{"rate":100.0,"change_from_previous":0.2,"nominal":1,"name":"Евро"}}}""",
            null,
        )
        store.recordRun(
            task.id, "currency", "SUCCESS",
            """{"base":"RUB","rates":{"USD":{"rate":91.0,"change_from_previous":0.5,"nominal":1,"name":"Доллар США"},"EUR":{"rate":101.0,"change_from_previous":0.3,"nominal":1,"name":"Евро"}}}""",
            null,
        )

        val s = tools.summary(task.id, 24).block()!!

        assertEquals(2, s["count"] as Int)
        assertNotNull(s["latest"], "latest — последний результат")
        @Suppress("UNCHECKED_CAST")
        val ratesList = s["ratesList"] as List<Map<String, Any?>>
        assertEquals(2, ratesList.size)
        assertEquals("EUR", ratesList[0]["code"], "список отсортирован по коду")
        assertEquals("USD", ratesList[1]["code"])
        assertEquals(101.0, ratesList[0]["rate"] as Double, "rate из последнего запуска")
        assertEquals(91.0, ratesList[1]["rate"] as Double)
    }

    @Test
    fun `news summary returns latest titles`() {
        val (tools, store) = tools()
        val task = store.addTask("новости", "news", 60, null)!!
        store.recordRun(task.id, "news", "SUCCESS", """[{"title":"Первая"},{"title":"Вторая"}]""", null)
        store.recordRun(task.id, "news", "SUCCESS", """[{"title":"Третья"},{"title":"Четвёртая"}]""", null)

        val s = tools.summary(task.id, 24).block()!!

        assertEquals(2, s["count"] as Int)
        @Suppress("UNCHECKED_CAST")
        val titles = s["latestTitles"] as List<String>
        assertEquals(listOf("Третья", "Четвёртая"), titles)
    }

    @Test
    fun `generic summary without taskId counts all runs`() {
        val (tools, store) = tools()
        val a = store.addTask("a", "weather", 60, null)!!
        val b = store.addTask("b", "news", 60, null)!!
        store.recordRun(a.id, "weather", "SUCCESS", """{"temperature":1.0}""", null)
        store.recordRun(b.id, "news", "SUCCESS", """[{"title":"X"}]""", null)

        val s = tools.summary(null, 24).block()!!
        assertEquals(2, s["count"] as Int)
        assertEquals(24, s["since"] as Int)
        assertTrue((s["latestAt"] as String).isNotBlank())
    }
}
