package com.example.llmagent.agent

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

/** Проект — задача НАД сессиями (иерархия Проект → Сессии). Строка таблицы `projects`. */
data class Project(
    val id: Long,
    val name: String,
    /** created_at/updated_at — UTC 'YYYY-MM-DD HH:MM:SS'. */
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Персистентность проектов (таблица `projects`, иерархия Проект → Сессии) и реестра
 * сессий (`chat_sessions`, создаётся здесь же — страховочно, как остальные store'ы).
 * Схема — в schema.sql (CREATE TABLE IF NOT EXISTS); здесь — страховочное создание
 * для старых файлов БД (тот же приём, что SessionFactsStore). «Без проекта» намеренно
 * НЕ создаётся (чистый старт; легаси-сессии удаляются миграцией SessionStore).
 *
 * Все методы fail-open: сбой БД — warn в лог и дефолт (пустой список / null / false / -1),
 * чтобы контроллер мог бросить 404/400, а не падать с исключением.
 */
@Component
class ProjectStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(ProjectStore::class.java)

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                updated_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_sessions (
                session_id TEXT PRIMARY KEY,
                project_id INTEGER NOT NULL REFERENCES projects(id),
                title TEXT,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_sessions_project_id ON chat_sessions (project_id)")
    }

    /** Создаёт проект и возвращает его id; -1 — сбой (fail-open). */
    fun create(name: String): Long {
        return try {
            val keyHolder = GeneratedKeyHolder()
            jdbc.update({ connection ->
                val ps = connection.prepareStatement(
                    "INSERT INTO projects (name) VALUES (?)",
                    arrayOf("id"),
                )
                ps.setString(1, name)
                ps
            }, keyHolder)
            keyHolder.key?.toLong() ?: -1L
        } catch (e: Exception) {
            log.warn("ProjectStore.create({}) не удался: {}", name, e.message)
            -1L
        }
    }

    /** Все проекты в порядке создания (ORDER BY created_at; при равенстве — по id). */
    fun list(): List<Project> {
        return try {
            jdbc.query(
                "SELECT id, name, created_at, updated_at FROM projects ORDER BY created_at ASC, id ASC",
                ROW_MAPPER,
            )
        } catch (e: Exception) {
            log.warn("ProjectStore.list() не удался: {}", e.message)
            emptyList()
        }
    }

    /** Проект по id; null — нет такого (или сбой БД). */
    fun get(id: Long): Project? {
        return try {
            jdbc.query(
                "SELECT id, name, created_at, updated_at FROM projects WHERE id = ?",
                ROW_MAPPER,
                id,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("ProjectStore.get({}) не удался: {}", id, e.message)
            null
        }
    }

    /** true, если проект с таким id существует. */
    fun exists(id: Long): Boolean = get(id) != null

    /**
     * Переименовывает проект (обновляет updated_at). true — переименован;
     * false — проекта нет или сбой БД.
     */
    fun updateName(id: Long, name: String): Boolean {
        return try {
            jdbc.update("UPDATE projects SET name = ?, updated_at = datetime('now') WHERE id = ?", name, id) > 0
        } catch (e: Exception) {
            log.warn("ProjectStore.updateName({}, {}) не удался: {}", id, name, e.message)
            false
        }
    }

    /**
     * Удаляет проект (строку projects). Сессии/сообщения/память проекта удаляет
     * НЕ это хранилище, а вызывающий код (ProjectController cascade): здесь только
     * сама строка проекта. true — удалена; false — нет или сбой БД.
     */
    fun delete(id: Long): Boolean {
        return try {
            jdbc.update("DELETE FROM projects WHERE id = ?", id) > 0
        } catch (e: Exception) {
            log.warn("ProjectStore.delete({}) не удался: {}", id, e.message)
            false
        }
    }

    private companion object {
        private val ROW_MAPPER = org.springframework.jdbc.core.RowMapper<Project> { rs, _ ->
            Project(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                createdAt = rs.getString("created_at"),
                updatedAt = rs.getString("updated_at"),
            )
        }
    }
}
