package com.example.llmagent.agent

import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

/** MCP-сервер (Day-16): агент-клиент подключается к Streamable-HTTP серверу инструментов. */
data class McpServer(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Персистентность MCP-серверов (таблица `mcp_servers`): список серверов с URL и флагом
 * включения. Схема — в schema.sql; здесь — страховочное создание для старых файлов БД.
 * Все методы fail-open: сбой БД не роняет агент — warn в лог и дефолт (null / пустой
 * список / false), как в остальных хранилищах.
 */
@Component
class McpServersStore(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(McpServersStore::class.java)

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS mcp_servers (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT NOT NULL UNIQUE,
                url        TEXT NOT NULL,
                enabled    INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Все серверы (ORDER BY id); сбой БД — пустой список. */
    fun list(): List<McpServer> {
        return try {
            jdbc.query(
                """
                SELECT id, name, url, enabled, created_at, updated_at
                FROM mcp_servers ORDER BY id
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
            )
        } catch (e: Exception) {
            log.warn("McpServers.list() не удался: {}", e.message)
            emptyList()
        }
    }

    /**
     * Добавляет MCP-сервер (enabled=0 по умолчанию). Пустые/blank name или url — null
     * (fail-open). Повторное имя (UNIQUE) либо сбой БД — null. Возвращает созданную
     * строку из БД (с реальным id).
     */
    fun add(name: String, url: String): McpServer? {
        val safeName = name.trim()
        val safeUrl = url.trim()
        if (safeName.isBlank() || safeUrl.isBlank()) return null
        val now = OffsetDateTime.now().toString()
        return try {
            val keyHolder = GeneratedKeyHolder()
            jdbc.update({ connection ->
                val ps = connection.prepareStatement(
                    "INSERT INTO mcp_servers (name, url, enabled, created_at, updated_at) VALUES (?, ?, 0, ?, ?)",
                    arrayOf("id"),
                )
                ps.setString(1, safeName)
                ps.setString(2, safeUrl)
                ps.setString(3, now)
                ps.setString(4, now)
                ps
            }, keyHolder)
            val id = keyHolder.key?.toLong()
                ?: return null
            jdbc.query(
                """
                SELECT id, name, url, enabled, created_at, updated_at
                FROM mcp_servers WHERE id = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                id,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("McpServers.add({}) не удался: {}", safeName, e.message)
            null
        }
    }

    /** Строка сервера по id; нет такой записи или сбой БД — null. */
    fun findById(id: Long): McpServer? {
        return try {
            jdbc.query(
                """
                SELECT id, name, url, enabled, created_at, updated_at
                FROM mcp_servers WHERE id = ?
                """.trimIndent(),
                { rs, _ -> mapRow(rs) },
                id,
            ).firstOrNull()
        } catch (e: Exception) {
            log.warn("McpServers.findById({}) не удался: {}", id, e.message)
            null
        }
    }

    /**
     * Включает/отключает сервер (updated_at перезаписывается). Нет такой записи или
     * сбой БД — null (fail-open). Возвращает итоговую строку из БД.
     */
    fun setEnabled(id: Long, enabled: Boolean): McpServer? {
        return try {
            val updated = jdbc.update(
                "UPDATE mcp_servers SET enabled = ?, updated_at = ? WHERE id = ?",
                if (enabled) 1 else 0,
                OffsetDateTime.now().toString(),
                id,
            )
            if (updated > 0) {
                jdbc.query(
                    """
                    SELECT id, name, url, enabled, created_at, updated_at
                    FROM mcp_servers WHERE id = ?
                    """.trimIndent(),
                    { rs, _ -> mapRow(rs) },
                    id,
                ).firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn("McpServers.setEnabled({}, {}) не удался: {}", id, enabled, e.message)
            null
        }
    }

    /** Удаляет сервер по id; true — удалён, false — нет такой записи (или сбой БД). */
    fun delete(id: Long): Boolean {
        return try {
            jdbc.update("DELETE FROM mcp_servers WHERE id = ?", id) > 0
        } catch (e: Exception) {
            log.warn("McpServers.delete({}) не удался: {}", id, e.message)
            false
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): McpServer = McpServer(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        url = rs.getString("url"),
        enabled = rs.getInt("enabled") != 0,
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
    )
}
