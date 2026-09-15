package com.example.llmagent.agent

import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Запись долговременной памяти агента (глобальная таблица, вне сессий). */
data class LongTermEntry(
    val id: Long,
    val sourceSessionId: String,
    val type: String,
    val key: String,
    val value: String,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Персистентность долговременной памяти агента (таблица `agent_long_term_memory`):
 * ГЛОБАЛЬНОЕ хранилище на все сессии — записи не скоупятся по сессии,
 * source_session_id лишь фиксирует, из какой сессии запись пришла. Уникальность
 * (type, key): повторное сохранение того же ключа ПЕРЕЗАПИСЫВАЕТ value (upsert,
 * created_at сохраняется). value обрезается до 2000 символов.
 *
 * Схема — в schema.sql; здесь — страховочное создание для старых файлов БД (тот же
 * приём, что SessionFactsStore). Все методы fail-open: сбой БД не роняет агент —
 * warn в лог и дефолт (null-строка в upsert означает сбой записи).
 */
@Component
class LongTermMemoryStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(LongTermMemoryStore::class.java)

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_long_term_memory (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                source_session_id TEXT NOT NULL,
                type              TEXT NOT NULL CHECK(type IN ('profile','decision','knowledge')),
                key               TEXT NOT NULL,
                value             TEXT NOT NULL,
                created_at        TEXT NOT NULL,
                updated_at        TEXT NOT NULL,
                UNIQUE(type, key)
            )
            """.trimIndent()
        )
    }

    /**
     * Вставляет запись или обновляет существующую по (type, key): перезаписываются
     * value/updated_at/source_session_id, created_at сохраняется прежним. Возвращает
     * итоговую строку из БД; id = -1 — запись не удалась (fail-open, не бросает).
     */
    fun upsert(sourceSessionId: String, type: String, key: String, value: String): LongTermEntry {
        val now = OffsetDateTime.now().toString()
        try {
            jdbc.update(
                """
                INSERT INTO agent_long_term_memory
                    (source_session_id, type, key, value, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(type, key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at,
                    source_session_id = excluded.source_session_id
                """.trimIndent(),
                sourceSessionId, type, key, value.take(VALUE_MAX_LENGTH), now, now,
            )
            return jdbc.query(
                """
                SELECT id, source_session_id, type, key, value, created_at, updated_at
                FROM agent_long_term_memory WHERE type = ? AND key = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                type, key,
            ).first()
        } catch (e: Exception) {
            log.warn(
                "LongTermMemory.upsert({}, {}, {}) не удался: {}",
                sourceSessionId, type, key, e.message,
            )
            return LongTermEntry(
                id = -1,
                sourceSessionId = sourceSessionId,
                type = type,
                key = key,
                value = value.take(VALUE_MAX_LENGTH),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    /** Все записи памяти, свежие сверху (ORDER BY updated_at DESC); сбой — пустой список. */
    fun listAll(): List<LongTermEntry> {
        return try {
            jdbc.query(
                """
                SELECT id, source_session_id, type, key, value, created_at, updated_at
                FROM agent_long_term_memory ORDER BY updated_at DESC
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
            )
        } catch (e: Exception) {
            log.warn("LongTermMemory.listAll() не удался: {}", e.message)
            emptyList()
        }
    }

    /** Удаляет запись по id; true — удалена, false — нет такой записи (или сбой БД). */
    fun delete(id: Long): Boolean {
        return try {
            jdbc.update("DELETE FROM agent_long_term_memory WHERE id = ?", id) > 0
        } catch (e: Exception) {
            log.warn("LongTermMemory.delete({}) не удался: {}", id, e.message)
            false
        }
    }

    /**
     * Удаляет ВСЕ записи долговременной памяти (глобальная очистка: все проекты и
     * сессии). Возвращает число удалённых строк; сбой БД — 0 (fail-open, не бросает).
     */
    fun clearAll(): Int {
        return try {
            jdbc.update("DELETE FROM agent_long_term_memory")
        } catch (e: Exception) {
            log.warn("LongTermMemory.clearAll() не удался: {}", e.message)
            0
        }
    }

    /** Число записей памяти; сбой БД — 0. */
    fun count(): Long {
        return try {
            jdbc.queryForObject("SELECT COUNT(*) FROM agent_long_term_memory", Long::class.java) ?: 0L
        } catch (e: Exception) {
            log.warn("LongTermMemory.count() не удался: {}", e.message)
            0L
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): LongTermEntry = LongTermEntry(
        id = rs.getLong("id"),
        sourceSessionId = rs.getString("source_session_id"),
        type = rs.getString("type"),
        key = rs.getString("key"),
        value = rs.getString("value"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
    )

    private companion object {
        const val VALUE_MAX_LENGTH = 2000
    }
}
