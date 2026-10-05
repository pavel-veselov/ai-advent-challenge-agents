package com.example.llmagent.agent

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Память задачи сессии (Day-25, task_memory): структурированное состояние диалога —
 * [goal] (цель), [clarifications] (уточнения пользователя) и [constraints]
 * (ограничения и термины). Одна строка на сессию (session_id — PRIMARY KEY);
 * строки нет → состояние пустое (GET /api/sessions/{sessionId}/task-memory отдаёт
 * пустую структуру со статусом 200, НЕ 404).
 *
 * Пишется ТОЛЬКО автоматически: LLM-извлечение после каждого завершённого обмена
 * (TaskMemoryService.updateFromExchange) — полная замена состояния. Пользователь
 * состояние через REST не правит (в отличие от task_state Day-13 — таблицы и код
 * независимы). Инъекция в промпт — блоком «=== ПАМЯТЬ ЗАДАЧИ ===» (AgentImpl).
 */
data class TaskMemory(
    val sessionId: String,
    val goal: String,
    val clarifications: List<String>,
    val constraints: List<String>,
    val updatedAt: String? = null,
) {
    /** Состояние пустое (нечего инъектировать в промпт)? */
    fun isEmpty(): Boolean = goal.isBlank() && clarifications.isEmpty() && constraints.isEmpty()
}

/**
 * Персистентность памяти задачи сессии (таблица `task_memory`; списки — JSON-массивы
 * строк, как notes в agent_working_memory). Схема — в schema.sql; здесь — страховочное
 * создание для старых файлов БД (тот же приём, что WorkingMemoryStore). Все методы
 * fail-open: сбой БД не роняет агент — warn в лог, get возвращает пустую структуру,
 * upsert — null. Битые JSON-массивы в БД разбираются как пустые списки (fail-open).
 */
@Component
class JdbcTaskMemoryStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(JdbcTaskMemoryStore::class.java)
    private val om = ObjectMapper()

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS task_memory (
                session_id     TEXT PRIMARY KEY,
                goal           TEXT NOT NULL DEFAULT '',
                clarifications TEXT NOT NULL DEFAULT '[]',
                constraints    TEXT NOT NULL DEFAULT '[]',
                updated_at     TEXT
            )
            """.trimIndent()
        )
    }

    /**
     * Память задачи сессии; строки нет (или БД недоступна) — пустая структура,
     * никогда не бросает.
     */
    fun get(sessionId: String): TaskMemory {
        return try {
            jdbc.query(
                """
                SELECT session_id, goal, clarifications, constraints, updated_at
                FROM task_memory WHERE session_id = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                sessionId,
            ).firstOrNull() ?: emptyMemory(sessionId)
        } catch (e: Exception) {
            log.warn("TaskMemory.get({}) не удался: {}", sessionId, e.message)
            emptyMemory(sessionId)
        }
    }

    /**
     * Полная замена состояния (INSERT OR REPLACE по session_id) — извлечение LLM
     * всегда возвращает ПОЛНОЕ обновлённое состояние, инкрементальных слияний нет.
     * Возвращает сохранённую строку; сбой БД — null (вызывающий код переводит это
     * в fail-open: событие task_memory_updated не эмитится).
     */
    fun upsert(
        sessionId: String,
        goal: String,
        clarifications: List<String>,
        constraints: List<String>,
    ): TaskMemory? {
        val now = OffsetDateTime.now().toString()
        val safeGoal = goal.take(TEXT_MAX_LENGTH)
        return try {
            jdbc.update(
                """
                INSERT INTO task_memory (session_id, goal, clarifications, constraints, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    goal = excluded.goal,
                    clarifications = excluded.clarifications,
                    constraints = excluded.constraints,
                    updated_at = excluded.updated_at
                """.trimIndent(),
                sessionId,
                safeGoal,
                om.writeValueAsString(clarifications),
                om.writeValueAsString(constraints),
                now,
            )
            TaskMemory(sessionId, safeGoal, clarifications, constraints, now)
        } catch (e: Exception) {
            log.warn("TaskMemory.upsert({}) не удался: {}", sessionId, e.message)
            null
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): TaskMemory = TaskMemory(
        sessionId = rs.getString("session_id"),
        goal = rs.getString("goal") ?: "",
        clarifications = parseList(rs.getString("clarifications")),
        constraints = parseList(rs.getString("constraints")),
        updatedAt = rs.getString("updated_at"),
    )

    /** Разбор JSON-массива строк; битый/пустой JSON — пустой список (fail-open). */
    private fun parseList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = om.readTree(raw)
            (0 until arr.size()).mapNotNull { i ->
                val el = arr.get(i)
                if (el != null && el.isTextual) el.asText() else null
            }
        } catch (e: Exception) {
            log.warn("TaskMemory: не удалось разобрать JSON-список ({}): {}", raw, e.message)
            emptyList()
        }
    }

    private fun emptyMemory(sessionId: String): TaskMemory = TaskMemory(
        sessionId = sessionId,
        goal = "",
        clarifications = emptyList(),
        constraints = emptyList(),
        updatedAt = null,
    )

    private companion object {
        const val TEXT_MAX_LENGTH = 2000
    }
}
