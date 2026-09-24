package com.example.llmagent.agent

import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

/**
 * Персистентность периодических задач планировщика (таблица `agent_scheduler_jobs`):
 * CRUD задач + выбор «дозревших» для запуска ([findDue]) и отметка о запуске ([markRun]).
 * Схема — в schema.sql; здесь — страховочное создание для старых файлов БД (тот же приём,
 * что McpServersStore). Все методы fail-open: сбой БД не роняет агент — warn в лог и null
 * (пустой список / false) как признак «не удалось».
 *
 * next_run_at считается как now + interval (ISO-строка через [OffsetDateTime]) и на create,
 * и на update: при изменении интервала расписание пересчитывается. Сравнение в [findDue] —
 * строковое по ISO-формату (тот же формат, что пишут create/update/markRun).
 */
@Component
class AgentSchedulerStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(AgentSchedulerStore::class.java)

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_scheduler_jobs (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                name             TEXT NOT NULL,
                interval_seconds INTEGER NOT NULL,
                prompt           TEXT NOT NULL,
                enabled          INTEGER NOT NULL DEFAULT 1,
                last_run_at      TEXT,
                next_run_at      TEXT,
                created_at       TEXT NOT NULL DEFAULT (datetime('now')),
                updated_at       TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
    }

    private val rowMapper = RowMapper<AgentSchedulerJob> { rs, _ ->
        AgentSchedulerJob(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            intervalSeconds = rs.getLong("interval_seconds"),
            prompt = rs.getString("prompt"),
            enabled = rs.getInt("enabled") != 0,
            lastRunAt = rs.getString("last_run_at"),
            nextRunAt = rs.getString("next_run_at"),
            createdAt = rs.getString("created_at"),
            updatedAt = rs.getString("updated_at"),
        )
    }

    private val columns =
        "id, name, interval_seconds, prompt, enabled, last_run_at, next_run_at, created_at, updated_at"

    /** Все задачи (ORDER BY id); сбой БД — пустой список. */
    fun list(): List<AgentSchedulerJob> {
        return try {
            jdbc.query(
                "SELECT $columns FROM agent_scheduler_jobs ORDER BY id",
                rowMapper,
            )
        } catch (e: Exception) {
            log.warn("AgentScheduler.list() не удался: {}", e.message)
            emptyList()
        }
    }

    /** Задача по id; нет такой записи или сбой БД — null. */
    fun find(id: Long): AgentSchedulerJob? {
        return try {
            jdbc.query(
                "SELECT $columns FROM agent_scheduler_jobs WHERE id = ?",
                rowMapper,
                id,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("AgentScheduler.find({}) не удался: {}", id, e.message)
            null
        }
    }

    /**
     * Создаёт задачу (enabled=1, next_run_at = now + interval). Пустые/blank name или prompt
     * либо intervalSeconds <= 0 — null (fail-open). Сбой БД — null. Возвращает созданную
     * строку из БД (с реальным id).
     */
    fun create(name: String, intervalSeconds: Long, prompt: String): AgentSchedulerJob? {
        val safeName = name.trim()
        val safePrompt = prompt.trim()
        if (safeName.isBlank() || safePrompt.isBlank() || intervalSeconds <= 0) return null
        val now = OffsetDateTime.now()
        val nextRun = now.plusSeconds(intervalSeconds).toString()
        return try {
            val keyHolder = GeneratedKeyHolder()
            jdbc.update({ connection ->
                val ps = connection.prepareStatement(
                    "INSERT INTO agent_scheduler_jobs (name, interval_seconds, prompt, enabled, next_run_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 1, ?, ?, ?)",
                    arrayOf("id"),
                )
                ps.setString(1, safeName)
                ps.setLong(2, intervalSeconds)
                ps.setString(3, safePrompt)
                ps.setString(4, nextRun)
                ps.setString(5, now.toString())
                ps.setString(6, now.toString())
                ps
            }, keyHolder)
            val id = keyHolder.key?.toLong() ?: return null
            find(id)
        } catch (e: Exception) {
            log.warn("AgentScheduler.create({}) не удался: {}", safeName, e.message)
            null
        }
    }

    /**
     * Частичное обновление задачи: переданные (не null) поля перезаписываются, остальные
     * сохраняются. next_run_at пересчитывается как now + (новый или прежний) интервал;
     * updated_at — now. Нет такой записи или сбой БД — null. Возвращает итоговую строку.
     */
    fun update(
        id: Long,
        name: String? = null,
        intervalSeconds: Long? = null,
        prompt: String? = null,
        enabled: Boolean? = null,
    ): AgentSchedulerJob? {
        val existing = find(id) ?: return null
        val effName = name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name
        val effInterval = (intervalSeconds ?: existing.intervalSeconds).coerceAtLeast(1)
        val effPrompt = prompt?.trim()?.takeIf { it.isNotEmpty() } ?: existing.prompt
        val effEnabled = enabled ?: existing.enabled
        val now = OffsetDateTime.now()
        val nextRun = now.plusSeconds(effInterval).toString()
        return try {
            jdbc.update(
                "UPDATE agent_scheduler_jobs SET name = ?, interval_seconds = ?, prompt = ?, enabled = ?, " +
                    "next_run_at = ?, updated_at = ? WHERE id = ?",
                effName,
                effInterval,
                effPrompt,
                if (effEnabled) 1 else 0,
                nextRun,
                now.toString(),
                id,
            )
            find(id)
        } catch (e: Exception) {
            log.warn("AgentScheduler.update({}) не удался: {}", id, e.message)
            null
        }
    }

    /** Удаляет задачу; true — удалена, false — нет такой записи (или сбой БД). */
    fun delete(id: Long): Boolean {
        return try {
            jdbc.update("DELETE FROM agent_scheduler_jobs WHERE id = ?", id) > 0
        } catch (e: Exception) {
            log.warn("AgentScheduler.delete({}) не удался: {}", id, e.message)
            false
        }
    }

    /**
     * «Дозревшие» задачи: enabled=1 и (next_run_at IS NULL ИЛИ next_run_at <= nowIso).
     * Строковое сравнение по ISO-формату — согласовано с форматом, которым пишут
     * create/update/markRun. Сбой БД — пустой список.
     */
    fun findDue(nowIso: String): List<AgentSchedulerJob> {
        return try {
            jdbc.query(
                "SELECT $columns FROM agent_scheduler_jobs WHERE enabled = 1 AND (next_run_at IS NULL OR next_run_at <= ?) " +
                    "ORDER BY id",
                rowMapper,
                nowIso,
            )
        } catch (e: Exception) {
            log.warn("AgentScheduler.findDue({}) не удался: {}", nowIso, e.message)
            emptyList()
        }
    }

    /**
     * Отметка о запуске задачи: last_run_at = nowIso, next_run_at = nextRunIso.
     * true — строка обновлена, false — записи нет или сбой БД.
     */
    fun markRun(id: Long, nowIso: String, nextRunIso: String): Boolean {
        return try {
            jdbc.update(
                "UPDATE agent_scheduler_jobs SET last_run_at = ?, next_run_at = ? WHERE id = ?",
                nowIso,
                nextRunIso,
                id,
            ) > 0
        } catch (e: Exception) {
            log.warn("AgentScheduler.markRun({}) не удался: {}", id, e.message)
            false
        }
    }
}
