package com.example.mcpserver.scheduler

import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

/** Задача планировщика (строка таблицы `scheduled_tasks`). */
data class ScheduledTask(
    val id: Long,
    val name: String,
    val source: String,
    val intervalSeconds: Int,
    val paramsJson: String?,
    val active: Boolean,
    val createdAt: String,
    val lastRunAt: String?,
)

/** Один запуск задачи (строка таблицы `task_runs`). */
data class TaskRun(
    val id: Long,
    val taskId: Long,
    val source: String,
    val status: String,
    val resultJson: String?,
    val error: String?,
    val startedAt: String,
)

/**
 * SQLite-хранилище планировщика (Day-18): задачи и историю их запусков. Схема
 * идемпотентно создаётся в init (CREATE TABLE IF NOT EXISTS), как в McpServersStore.
 *
 * Все методы fail-open: сбой БД не роняет сервер — warn в лог и дефолт (null / пустой
 * список / false). Временные метки — ISO-8601 UTC (Instant.now().toString()).
 */
@Component
class SchedulerStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(SchedulerStore::class.java)

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS scheduled_tasks (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                name             TEXT NOT NULL,
                source           TEXT NOT NULL,
                interval_seconds INTEGER NOT NULL,
                params_json      TEXT,
                active           INTEGER DEFAULT 1,
                created_at       TEXT NOT NULL,
                last_run_at      TEXT
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS task_runs (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id     INTEGER NOT NULL,
                source      TEXT,
                status      TEXT,
                result_json TEXT,
                error       TEXT,
                started_at  TEXT
            )
            """.trimIndent()
        )
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_task_runs_task_id ON task_runs (task_id)")
    }

    /** Все задачи (ORDER BY id); сбой БД — пустой список. */
    fun listTasks(): List<ScheduledTask> {
        return try {
            jdbc.query(
                """
                SELECT id, name, source, interval_seconds, params_json, active, created_at, last_run_at
                FROM scheduled_tasks ORDER BY id
                """.trimIndent(),
                { rs, _ -> mapTask(rs) },
            )
        } catch (e: Exception) {
            log.warn("SchedulerStore.listTasks() не удался: {}", e.message)
            emptyList()
        }
    }

    /** Только активные задачи (для планировщика на старте). */
    fun listActiveTasks(): List<ScheduledTask> = listTasks().filter { it.active }

    /** Задача по id; нет такой записи или сбой БД — null. */
    fun findTask(id: Long): ScheduledTask? {
        return try {
            jdbc.query(
                """
                SELECT id, name, source, interval_seconds, params_json, active, created_at, last_run_at
                FROM scheduled_tasks WHERE id = ?
                """.trimIndent(),
                { rs, _ -> mapTask(rs) },
                id,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("SchedulerStore.findTask({}) не удался: {}", id, e.message)
            null
        }
    }

    /**
     * Добавляет задачу (active=1). Пустые/blank name или source, либо interval < 1 — null
     * (fail-open). Возвращает созданную строку из БД (с реальным id).
     */
    fun addTask(name: String, source: String, intervalSeconds: Int, paramsJson: String?): ScheduledTask? {
        val safeName = name.trim()
        val safeSource = source.trim().lowercase()
        if (safeName.isBlank() || safeSource.isBlank() || intervalSeconds < 1) return null
        val now = nowIso()
        return try {
            val keyHolder = GeneratedKeyHolder()
            jdbc.update({ connection ->
                val ps = connection.prepareStatement(
                    "INSERT INTO scheduled_tasks (name, source, interval_seconds, params_json, active, created_at) " +
                        "VALUES (?, ?, ?, ?, 1, ?)",
                    arrayOf("id"),
                )
                ps.setString(1, safeName)
                ps.setString(2, safeSource)
                ps.setInt(3, intervalSeconds)
                ps.setString(4, paramsJson)
                ps.setString(5, now)
                ps
            }, keyHolder)
            val id = keyHolder.key?.toLong() ?: return null
            findTask(id)
        } catch (e: Exception) {
            log.warn("SchedulerStore.addTask({}, {}) не удался: {}", safeName, safeSource, e.message)
            null
        }
    }

    /** Меняет интервал задачи; нет такой записи или сбой БД — null. */
    fun updateInterval(id: Long, intervalSeconds: Int): ScheduledTask? {
        return try {
            val updated = jdbc.update(
                "UPDATE scheduled_tasks SET interval_seconds = ? WHERE id = ?",
                intervalSeconds,
                id,
            )
            if (updated > 0) findTask(id) else null
        } catch (e: Exception) {
            log.warn("SchedulerStore.updateInterval({}, {}) не удался: {}", id, intervalSeconds, e.message)
            null
        }
    }

    /** Включает/отключает задачу; нет такой записи или сбой БД — null. */
    fun setActive(id: Long, active: Boolean): ScheduledTask? {
        return try {
            val updated = jdbc.update(
                "UPDATE scheduled_tasks SET active = ? WHERE id = ?",
                if (active) 1 else 0,
                id,
            )
            if (updated > 0) findTask(id) else null
        } catch (e: Exception) {
            log.warn("SchedulerStore.setActive({}, {}) не удался: {}", id, active, e.message)
            null
        }
    }

    /** Удаляет задачу; true — удалена, false — нет записи (или сбой БД). */
    fun deleteTask(id: Long): Boolean {
        return try {
            jdbc.update("DELETE FROM scheduled_tasks WHERE id = ?", id) > 0
        } catch (e: Exception) {
            log.warn("SchedulerStore.deleteTask({}) не удался: {}", id, e.message)
            false
        }
    }

    /** Фиксирует время последнего запуска задачи. */
    fun markLastRun(id: Long, lastRunAt: String) {
        try {
            jdbc.update("UPDATE scheduled_tasks SET last_run_at = ? WHERE id = ?", lastRunAt, id)
        } catch (e: Exception) {
            log.warn("SchedulerStore.markLastRun({}) не удался: {}", id, e.message)
        }
    }

    /**
     * Пишет строку в task_runs и обновляет last_run_at задачи. Возвращает id строки
     * или null при сбое БД.
     */
    fun recordRun(taskId: Long, source: String, status: String, resultJson: String?, error: String?): Long? {
        return try {
            val keyHolder = GeneratedKeyHolder()
            jdbc.update({ connection ->
                val ps = connection.prepareStatement(
                    "INSERT INTO task_runs (task_id, source, status, result_json, error, started_at) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf("id"),
                )
                ps.setLong(1, taskId)
                ps.setString(2, source)
                ps.setString(3, status)
                ps.setString(4, resultJson)
                ps.setString(5, error)
                ps.setString(6, nowIso())
                ps
            }, keyHolder)
            val id = keyHolder.key?.toLong()
            markLastRun(taskId, nowIso())
            id
        } catch (e: Exception) {
            log.warn("SchedulerStore.recordRun({}) не удался: {}", taskId, e.message)
            null
        }
    }

    /**
     * Список запусков: по конкретной задаче (taskId != null) либо по всем, в окне
     * [sinceHours] часов назад. Сбой БД — пустой список.
     */
    fun listRuns(taskId: Long?, sinceHours: Int): List<TaskRun> {
        val since = Instant.now().minus(sinceHours.toLong(), ChronoUnit.HOURS).toString()
        return try {
            if (taskId != null) {
                jdbc.query(
                    """
                    SELECT id, task_id, source, status, result_json, error, started_at
                    FROM task_runs WHERE task_id = ? AND started_at >= ? ORDER BY id
                    """.trimIndent(),
                    { rs, _ -> mapRun(rs) },
                    taskId,
                    since,
                )
            } else {
                jdbc.query(
                    """
                    SELECT id, task_id, source, status, result_json, error, started_at
                    FROM task_runs WHERE started_at >= ? ORDER BY id
                    """.trimIndent(),
                    { rs, _ -> mapRun(rs) },
                    since,
                )
            }
        } catch (e: Exception) {
            log.warn("SchedulerStore.listRuns({}, {}) не удался: {}", taskId, sinceHours, e.message)
            emptyList()
        }
    }

    /** Количество запусков задачи; сбой БД — 0. */
    fun countRuns(taskId: Long): Int {
        return try {
            jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE task_id = ?", Int::class.java, taskId) ?: 0
        } catch (e: Exception) {
            log.warn("SchedulerStore.countRuns({}) не удался: {}", taskId, e.message)
            0
        }
    }

    private fun mapTask(rs: ResultSet): ScheduledTask = ScheduledTask(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        source = rs.getString("source"),
        intervalSeconds = rs.getInt("interval_seconds"),
        paramsJson = rs.getString("params_json"),
        active = rs.getInt("active") != 0,
        createdAt = rs.getString("created_at"),
        lastRunAt = rs.getString("last_run_at"),
    )

    private fun mapRun(rs: ResultSet): TaskRun = TaskRun(
        id = rs.getLong("id"),
        taskId = rs.getLong("task_id"),
        source = rs.getString("source"),
        status = rs.getString("status"),
        resultJson = rs.getString("result_json"),
        error = rs.getString("error"),
        startedAt = rs.getString("started_at"),
    )

    private fun nowIso(): String = Instant.now().toString()
}
