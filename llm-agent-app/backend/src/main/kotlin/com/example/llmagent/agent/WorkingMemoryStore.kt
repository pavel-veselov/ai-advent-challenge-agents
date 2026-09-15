package com.example.llmagent.agent

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Рабочая память агента ПРОЕКТА: текущая задача (может отсутствовать) + заметки. */
data class WorkingMemory(val task: String?, val notes: List<String>)

/**
 * Персистентность рабочей памяти агента (таблица `agent_working_memory`, одна строка
 * на ПРОЕКТ): текущая задача и заметки — JSON-массив строк. Память — память ПРОЕКТА
 * (общая для всех сессий проекта), ключ — project_id (TEXT, без кастов). Добавление
 * заметки — чтение JSON, добавление элемента, запись обратно (атомарности не требуется:
 * единственный писатель — агент одного run). Схема — в schema.sql; здесь — страховочное
 * создание для старых файлов БД (тот же приём, что SessionFactsStore).
 *
 * Миграция старых файлов БД: таблица могла существовать со СТАРЫМ ключом session_id
 * (память per-session). По решению пользователя старые сессии удаляются — таблица
 * пересоздаётся под `project_id` с ПУСТЫМ содержимым (legaсy-память стирается).
 *
 * Все методы fail-open: сбой БД не роняет агент — warn в лог и дефолт/ничего.
 */
@Component
class WorkingMemoryStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(WorkingMemoryStore::class.java)
    private val om = ObjectMapper()

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_working_memory (
                project_id TEXT PRIMARY KEY,
                task       TEXT,
                notes      TEXT NOT NULL DEFAULT '[]',
                updated_at TEXT
            )
            """.trimIndent()
        )
        // Миграция старых файлов БД: таблица с session_id-PK (per-session память).
        // Пересоздаём её под project_id — старые строки удаляются (см. KDoc класса).
        if (!hasColumn("project_id")) {
            log.warn("WorkingMemory: старая схема (session_id-PK) — сбрасываю таблицу (per-session память удаляется)")
            jdbc.execute("DROP TABLE IF EXISTS agent_working_memory")
            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS agent_working_memory (
                    project_id TEXT PRIMARY KEY,
                    task       TEXT,
                    notes      TEXT NOT NULL DEFAULT '[]',
                    updated_at TEXT
                )
                """.trimIndent()
            )
        }
    }

    /** Память проекта; строки нет (или БД недоступна) — пустая структура, никогда не бросает. */
    fun get(projectId: String): WorkingMemory {
        return try {
            jdbc.query(
                "SELECT task, notes FROM agent_working_memory WHERE project_id = ?",
                { rs, _ ->
                    WorkingMemory(
                        task = rs.getString("task"),
                        notes = parseNotes(rs.getString("notes")),
                    )
                },
                projectId,
            ).firstOrNull() ?: WorkingMemory(task = null, notes = emptyList())
        } catch (e: Exception) {
            log.warn("WorkingMemory.get({}) не удался: {}", projectId, e.message)
            WorkingMemory(task = null, notes = emptyList())
        }
    }

    /**
     * Записывает текущую задачу проекта (строку создаёт, заметки не трогает).
     * task = null сбрасывает задачу. Fail-open: сбой — warn, без исключения.
     */
    fun setTask(projectId: String, task: String?) {
        try {
            jdbc.update(
                """
                INSERT INTO agent_working_memory (project_id, task, notes, updated_at)
                VALUES (?, ?, '[]', ?)
                ON CONFLICT(project_id) DO UPDATE
                    SET task = excluded.task, updated_at = excluded.updated_at
                """.trimIndent(),
                projectId, task, OffsetDateTime.now().toString(),
            )
        } catch (e: Exception) {
            log.warn("WorkingMemory.setTask({}) не удался: {}", projectId, e.message)
        }
    }

    /**
     * Добавляет заметку в конец JSON-массива (заметка обрезается до 1000 символов).
     * Задача (task) сохраняется прежней. Fail-open: сбой — warn, заметка не пишется.
     */
    fun appendNote(projectId: String, note: String) {
        try {
            val current = get(projectId)
            val notes = current.notes + note.take(NOTE_MAX_LENGTH)
            jdbc.update(
                """
                INSERT INTO agent_working_memory (project_id, task, notes, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE
                    SET notes = excluded.notes, updated_at = excluded.updated_at
                """.trimIndent(),
                projectId, current.task, om.writeValueAsString(notes), OffsetDateTime.now().toString(),
            )
        } catch (e: Exception) {
            log.warn("WorkingMemory.appendNote({}) не удался: {}", projectId, e.message)
        }
    }

    /** Сбрасывает память проекта: task = NULL, notes = '[]' (строку оставляет). */
    fun clear(projectId: String) {
        try {
            jdbc.update(
                """
                INSERT INTO agent_working_memory (project_id, task, notes, updated_at)
                VALUES (?, NULL, '[]', ?)
                ON CONFLICT(project_id) DO UPDATE
                    SET task = NULL, notes = '[]', updated_at = excluded.updated_at
                """.trimIndent(),
                projectId, OffsetDateTime.now().toString(),
            )
        } catch (e: Exception) {
            log.warn("WorkingMemory.clear({}) не удался: {}", projectId, e.message)
        }
    }

    /** Удаляет память проекта целиком (для DELETE /api/projects/{id}). */
    fun deleteByProject(projectId: String) {
        try {
            jdbc.update("DELETE FROM agent_working_memory WHERE project_id = ?", projectId)
        } catch (e: Exception) {
            log.warn("WorkingMemory.deleteByProject({}) не удался: {}", projectId, e.message)
        }
    }

    /** Разбор JSON-массива строк; битый/пустой JSON — пустой список (fail-open). */
    private fun parseNotes(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = om.readTree(raw)
            (0 until arr.size()).map { arr.get(it).asText() }
        } catch (e: Exception) {
            log.warn("WorkingMemory: не удалось разобрать notes ({}): {}", raw, e.message)
            emptyList()
        }
    }

    private fun hasColumn(name: String): Boolean =
        jdbc.query("PRAGMA table_info(agent_working_memory)") { rs, _ -> rs.getString("name") }
            .any { it == name }

    private companion object {
        const val NOTE_MAX_LENGTH = 1000
    }
}
