package com.example.llmagent.agent

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component

/**
 * Персистентность «липких фактов» сессии (таблица `session_facts`, ключ-значение).
 * Факты извлекаются LLM при стратегии sticky_facts (см. AgentImpl) и подмешиваются
 * в контекст системным сообщением. Схема — в schema.sql; здесь — страховочное создание
 * для старых файлов БД (тот же приём, что SessionCompressionStore).
 *
 * Порядок строк хранит порядок вставки последнего [replaceAll] — getAll возвращает
 * LinkedHashMap, где факты идут в исходном порядке карты (ORDER BY rowid: после
 * delete+insert строки получают свежие rowid в порядке вставки).
 */
@Component
class SessionFactsStore(private val jdbc: JdbcTemplate) {

    init {
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS session_facts (
                session_id TEXT NOT NULL,
                fact_key   TEXT NOT NULL,
                fact_value TEXT NOT NULL,
                updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                PRIMARY KEY(session_id, fact_key)
            )
            """.trimIndent()
        )
    }

    /** Все факты сессии в порядке вставки (LinkedHashMap); пусто — нет ни одного факта. */
    fun getAll(sessionId: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val rows = jdbc.query(
            "SELECT fact_key, fact_value FROM session_facts WHERE session_id = ? ORDER BY rowid",
            { rs, _ -> rs.getString("fact_key") to rs.getString("fact_value") },
            sessionId,
        )
        for ((key, value) in rows) {
            result[key] = value
        }
        return result
    }

    /**
     * Полная замена фактов сессии ОДНОЙ транзакцией: DELETE всех строк + INSERT новых
     * в порядке карты (LinkedHashMap → в том же порядке сохраняются в БД). При сбое на
     * любом шаге — rollback, прежние факты не теряются (fail-open ниже по стеку).
     * Транзакция ведётся самим хранилищем на соединении DataSourceUtils: конструктор
     * принимает только JdbcTemplate, как и остальные хранилища (без менеджера транзакций).
     */
    fun replaceAll(sessionId: String, facts: Map<String, String>) {
        val ds = jdbc.dataSource ?: error("Нет DataSource для транзакции фактов")
        val con = DataSourceUtils.getConnection(ds)
        val previousAutoCommit = con.autoCommit
        var committed = false
        try {
            con.autoCommit = false
            con.prepareStatement("DELETE FROM session_facts WHERE session_id = ?").use { st ->
                st.setString(1, sessionId)
                st.executeUpdate()
            }
            con.prepareStatement(
                "INSERT INTO session_facts (session_id, fact_key, fact_value) VALUES (?, ?, ?)"
            ).use { st ->
                for ((key, value) in facts) {
                    st.setString(1, sessionId)
                    st.setString(2, key)
                    st.setString(3, value)
                    st.addBatch()
                }
                st.executeBatch()
            }
            con.commit()
            committed = true
        } finally {
            if (!committed) {
                try {
                    con.rollback()
                } catch (_: Exception) {
                    // соединение уже закрыто/откачено — игнорируем
                }
            }
            try {
                con.autoCommit = previousAutoCommit
            } catch (_: Exception) {
                // не критично: соединение освобождается
            }
            DataSourceUtils.releaseConnection(con, ds)
        }
    }

    /** Очищает все факты сессии (эквивалент replaceAll с пустой картой). */
    fun clear(sessionId: String) {
        jdbc.update("DELETE FROM session_facts WHERE session_id = ?", sessionId)
    }

    /** Удаляет факты сессии (вызывается при DELETE /api/sessions/{sessionId}). */
    fun remove(sessionId: String) {
        jdbc.update("DELETE FROM session_facts WHERE session_id = ?", sessionId)
    }
}
