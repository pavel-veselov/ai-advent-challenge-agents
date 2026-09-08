package com.example.llmagent.agent

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component

data class ChatMessage(val role: String, val content: String)

/**
 * История диалогов в SQLite (таблица chat_messages) через JdbcTemplate.
 * Данные переживают перезапуск backend: файл БД по умолчанию ./data/llm-agent.db
 * (переопределяется переменной окружения SQLITE_DB_PATH). Схема создаётся
 * автоматически из schema.sql при старте приложения.
 */
@Component
class SessionStore(private val jdbcTemplate: JdbcTemplate) {

    private val rowMapper = RowMapper<ChatMessage> { rs, _ ->
        ChatMessage(rs.getString("role"), rs.getString("content"))
    }

    /** Добавляет сообщение в историю сессии (порядок чтения — порядок вставки по id). */
    fun append(sessionId: String, role: String, content: String) {
        jdbcTemplate.update(
            "INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)",
            sessionId, role, content,
        )
    }

    /** Возвращает историю сессии в порядке вставки (ORDER BY id). */
    fun get(sessionId: String): List<ChatMessage> =
        jdbcTemplate.query(
            "SELECT role, content FROM chat_messages WHERE session_id = ? ORDER BY id",
            rowMapper,
            sessionId,
        )

    /** Удаляет всю историю сессии. */
    fun delete(sessionId: String) {
        jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId)
    }
}
