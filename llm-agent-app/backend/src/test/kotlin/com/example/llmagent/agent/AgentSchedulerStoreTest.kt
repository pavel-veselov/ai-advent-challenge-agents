package com.example.llmagent.agent

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate

/** Юнит-тесты хранилища периодических задач планировщика (Day-17/18): создание (enabled=1,
 *  next_run_at = now + interval), список (ORDER BY id), чтение по id, частичное обновление
 *  (интервал пересчитывает next_run_at, переданные поля перезаписываются), удаление,
 *  findDue (enabled + next_run_at <= now ИЛИ NULL), markRun (last_run_at/next_run_at),
 *  персистентность на том же файле БД. */
class AgentSchedulerStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): AgentSchedulerStore =
        AgentSchedulerStore(SqliteTestSupport.jdbc(tmpDir.resolve("sched-${UUID.randomUUID()}.db")))

    private fun storeWithJdbc(): Pair<AgentSchedulerStore, JdbcTemplate> {
        val jdbc = SqliteTestSupport.jdbc(tmpDir.resolve("sched-${UUID.randomUUID()}.db"))
        return AgentSchedulerStore(jdbc) to jdbc
    }

    @Test
    fun `list is empty for new store`() {
        assertTrue(store().list().isEmpty(), "у пустого хранилища — пустой список")
    }

    @Test
    fun `create returns job enabled by default with next_run_at set`() {
        val f = store()
        val before = OffsetDateTime.now()
        val job = f.create("job", 300, "промпт")!!
        assertNotNull(job, "create возвращает созданную запись")
        assertTrue(job.id > 0, "записи назначен реальный id из БД")
        assertEquals("job", job.name)
        assertEquals(300, job.intervalSeconds)
        assertEquals("промпт", job.prompt)
        assertTrue(job.enabled, "новая задача включена (enabled=1)")
        assertNull(job.lastRunAt, "last_run_at ещё не было")

        val next = OffsetDateTime.parse(job.nextRunAt!!)
        assertTrue(next.isAfter(before), "next_run_at = now + interval (в будущем)")
        assertTrue(job.createdAt.isNotBlank() && job.updatedAt.isNotBlank())
    }

    @Test
    fun `create trims name and prompt and rejects blank or non-positive interval`() {
        val f = store()
        val job = f.create("  job  ", 60, "  промпт  ")!!
        assertEquals("job", job.name, "name обрезан")
        assertEquals("промпт", job.prompt, "prompt обрезан")

        assertNull(f.create("", 60, "промпт"), "пустое имя — null")
        assertNull(f.create("   ", 60, "промпт"), "blank имя — null")
        assertNull(f.create("job", 60, ""), "пустой prompt — null")
        assertNull(f.create("job", 60, "   "), "blank prompt — null")
        assertNull(f.create("job", 0, "промпт"), "zero interval — null")
        assertNull(f.create("job", -5, "промпт"), "negative interval — null")
    }

    @Test
    fun `list orders by id`() {
        val f = store()
        val a = f.create("a", 60, "п")!!
        val b = f.create("b", 60, "п")!!
        val c = f.create("c", 60, "п")!!
        assertEquals(listOf(a.id, b.id, c.id), f.list().map { it.id }, "порядок — по id")
    }

    @Test
    fun `find returns existing or null`() {
        val f = store()
        val a = f.create("a", 60, "п")!!
        assertEquals(a.id, f.find(a.id)?.id)
        assertEquals("a", f.find(a.id)?.name)
        assertNull(f.find(999999), "несуществующий id — null")
    }

    @Test
    fun `update partial fields keeps others and recomputes next_run_at`() {
        val f = store()
        val job = f.create("job", 300, "промпт")!!

        val renamed = f.update(job.id, name = "новое имя", prompt = "новый промпт")!!
        assertEquals("новое имя", renamed.name)
        assertEquals("новый промпт", renamed.prompt)
        assertEquals(300, renamed.intervalSeconds, "интервал не изменён")
        assertTrue(renamed.enabled, "флаг не изменён")

        val before = OffsetDateTime.now()
        val intervalChanged = f.update(job.id, intervalSeconds = 60)!!
        assertEquals(60, intervalChanged.intervalSeconds)
        assertTrue(
            OffsetDateTime.parse(intervalChanged.nextRunAt!!).isAfter(before),
            "смена интервала пересчитывает next_run_at = now + 60",
        )

        val disabled = f.update(job.id, enabled = false)!!
        assertFalse(disabled.enabled, "задача выключена")
        assertEquals("новое имя", disabled.name, "остальные поля не тронуты")
    }

    @Test
    fun `update nonexistent returns null`() {
        assertNull(store().update(999, name = "x"), "нет записи — null")
    }

    @Test
    fun `delete returns true then false on second delete`() {
        val f = store()
        val job = f.create("job", 60, "п")!!
        assertTrue(f.delete(job.id), "первое удаление успешно")
        assertFalse(f.delete(job.id), "повторное удаление — false (нет записи)")
        assertTrue(f.list().isEmpty())
        assertNull(f.find(job.id))
    }

    @Test
    fun `findDue returns enabled jobs whose next_run_at is past or null`() {
        val (f, jdbc) = storeWithJdbc()
        val past = f.create("прошлая", 1, "п")!!
        val future = f.create("будущая", 3600, "п")!!
        // Задача с NULL next_run_at (ещё ни разу не запускалась) — вручную, мимо create.
        jdbc.update(
            "INSERT INTO agent_scheduler_jobs (name, interval_seconds, prompt, enabled, next_run_at) VALUES ('nul', 60, 'п', 1, NULL)",
        )
        val nullJobId = jdbc.queryForObject("SELECT MAX(id) FROM agent_scheduler_jobs", Long::class.java)!!

        val now = OffsetDateTime.now()
        val due = f.findDue(now.plusSeconds(30).toString())
        assertTrue(due.any { it.id == past.id }, "прошлая задача (next_run_at <= now) должна быть дозревшей")
        assertTrue(due.any { it.id == nullJobId }, "next_run_at IS NULL — дозревшая")
        assertTrue(due.none { it.id == future.id }, "будущая задача (next_run_at > now) не дозревшая")

        // Выключенная задача не дозревшая.
        f.update(past.id, enabled = false)
        val dueAfterDisable = f.findDue(now.plusSeconds(30).toString())
        assertFalse(dueAfterDisable.any { it.id == past.id }, "выключенная задача не дозревшая")
        assertTrue(dueAfterDisable.any { it.id == nullJobId }, "включённая NULL остаётся дозревшей")
    }

    @Test
    fun `markRun sets last_run_at and next_run_at`() {
        val f = store()
        val job = f.create("job", 60, "п")!!
        val now = OffsetDateTime.now()
        assertTrue(f.markRun(job.id, now.toString(), now.plusSeconds(60).toString()))

        val reread = f.find(job.id)!!
        assertEquals(now.toString(), reread.lastRunAt, "last_run_at записан")
        assertEquals(now.plusSeconds(60).toString(), reread.nextRunAt, "next_run_at сдвинут вперёд")
        assertFalse(f.markRun(999, now.toString(), now.plusSeconds(60).toString()), "нет записи — false")
    }

    @Test
    fun `jobs persist across store restart on the same db file`() {
        val dbFile = tmpDir.resolve("sched-persist-${UUID.randomUUID()}.db")
        val first = AgentSchedulerStore(SqliteTestSupport.jdbc(dbFile))
        val job = first.create("persist", 60, "промпт")!!
        first.update(job.id, enabled = false)

        // «Перезапуск»: новый store поверх того же файла (схема CREATE IF NOT EXISTS)
        val second = AgentSchedulerStore(SqliteTestSupport.jdbc(dbFile))
        val reread = second.find(job.id)
        assertNotNull(reread, "задача переживает перезапуск хранилища")
        assertEquals("persist", reread!!.name)
        assertEquals(60, reread.intervalSeconds)
        assertFalse(reread.enabled, "флаг enabled сохраняется")
        assertEquals(1, second.list().size)
    }
}
