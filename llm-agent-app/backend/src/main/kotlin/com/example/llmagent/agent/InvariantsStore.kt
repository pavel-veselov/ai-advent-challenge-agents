package com.example.llmagent.agent

import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

/** Инвариант проекта (Day-14): обязательное ограничение агента — категория + текст. */
data class Invariant(
    val id: Long,
    val projectId: String,
    val category: String?,
    val text: String,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Персистентность инвариантов проекта (таблица `agent_invariants`): обязательные
 * ограничения, которые ассистент НЕ имеет права нарушать (архитектура, технические
 * решения, стек, бизнес-правила). Инварианты хранятся ОТДЕЛЬНО от диалога и скоупятся
 * ПО ПРОЕКТУ (project_id TEXT, как рабочая память) — общая для всех сессий проекта.
 *
 * Схема — в schema.sql; здесь — страховочное создание для старых файлов БД (тот же
 * приём, что LongTermMemoryStore). Все методы fail-open: сбой БД не роняет агент —
 * warn в лог и дефолт (null-инвариант / пустой список / false / 0 — сбой операции).
 * Текст обрезается до [TEXT_MAX_LENGTH] символов.
 */
@Component
class InvariantsStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(InvariantsStore::class.java)

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_invariants (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id TEXT NOT NULL,
                category   TEXT,
                text       TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Инварианты проекта (везде ORDER BY id); сбой БД — пустой список. */
    fun list(projectId: String): List<Invariant> {
        return try {
            jdbc.query(
                """
                SELECT id, project_id, category, text, created_at, updated_at
                FROM agent_invariants WHERE project_id = ? ORDER BY id
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                projectId,
            )
        } catch (e: Exception) {
            log.warn("Invariants.list({}) не удался: {}", projectId, e.message)
            emptyList()
        }
    }

    /**
     * Добавляет инвариант проекта. Пустой текст или сбой БД — null (fail-open, не бросает).
     * Возвращает созданную строку из БД (с реальным id).
     */
    fun add(projectId: String, category: String?, text: String): Invariant? {
        val safeText = text.take(TEXT_MAX_LENGTH)
        if (safeText.isBlank()) return null
        val now = OffsetDateTime.now().toString()
        return try {
            val keyHolder = GeneratedKeyHolder()
            jdbc.update({ connection ->
                val ps = connection.prepareStatement(
                    "INSERT INTO agent_invariants (project_id, category, text, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    arrayOf("id"),
                )
                ps.setString(1, projectId)
                ps.setString(2, normalize(category))
                ps.setString(3, safeText)
                ps.setString(4, now)
                ps.setString(5, now)
                ps
            }, keyHolder)
            val id = keyHolder.key?.toLong()
                ?: return null
            jdbc.query(
                """
                SELECT id, project_id, category, text, created_at, updated_at
                FROM agent_invariants WHERE id = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                id,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("Invariants.add({}) не удался: {}", projectId, e.message)
            null
        }
    }

    /**
     * Обновляет инвариант по id (updated_at перезаписывается, created_at сохраняется).
     * Пустой текст — null (в этом случае лучше 400 на контроллере); нет такой записи
     * или сбой БД — null (fail-open, не бросает). Возвращает итоговую строку из БД.
     */
    fun update(id: Long, category: String?, text: String): Invariant? {
        val safeText = text.take(TEXT_MAX_LENGTH)
        if (safeText.isBlank()) return null
        return try {
            val updated = jdbc.update(
                """
                UPDATE agent_invariants
                SET category = ?, text = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent(),
                normalize(category), safeText, OffsetDateTime.now().toString(), id,
            )
            if (updated > 0) {
                jdbc.query(
                    """
                    SELECT id, project_id, category, text, created_at, updated_at
                    FROM agent_invariants WHERE id = ?
                    """.trimIndent(),
                    { rs, _ -> mapRow(rs) },
                    id,
                ).first()
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn("Invariants.update({}) не удался: {}", id, e.message)
            null
        }
    }

    /** Удаляет инвариант по id; true — удалён, false — нет такой записи (или сбой БД). */
    fun delete(id: Long): Boolean {
        return try {
            jdbc.update("DELETE FROM agent_invariants WHERE id = ?", id) > 0
        } catch (e: Exception) {
            log.warn("Invariants.delete({}) не удался: {}", id, e.message)
            false
        }
    }

    /** Число инвариантов проекта; сбой БД — 0. */
    fun count(projectId: String): Int {
        return try {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_invariants WHERE project_id = ?",
                Int::class.java,
                projectId,
            ) ?: 0
        } catch (e: Exception) {
            log.warn("Invariants.count({}) не удался: {}", projectId, e.message)
            0
        }
    }

    /** Удаляет инварианты проекта целиком (для DELETE /api/projects/{id}). */
    fun deleteByProject(projectId: String) {
        try {
            jdbc.update("DELETE FROM agent_invariants WHERE project_id = ?", projectId)
        } catch (e: Exception) {
            log.warn("Invariants.deleteByProject({}) не удался: {}", projectId, e.message)
        }
    }

    /** Пустая/blank категория — null (в БД храним NULL, а не пустую строку). */
    private fun normalize(category: String?): String? = category?.takeIf { it.isNotBlank() }

    private fun mapRow(rs: java.sql.ResultSet): Invariant = Invariant(
        id = rs.getLong("id"),
        projectId = rs.getString("project_id"),
        category = rs.getString("category"),
        text = rs.getString("text"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
    )

    private companion object {
        const val TEXT_MAX_LENGTH = 2000
    }
}
