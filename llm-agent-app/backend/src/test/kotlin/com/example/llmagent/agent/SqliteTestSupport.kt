package com.example.llmagent.agent

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Path

/**
 * Вспомогательный код для юнит-тестов SQLite-хранилища: поднимает JdbcTemplate
 * поверх файла БД и создаёт ту же схему, что и schema.sql в production.
 */
object SqliteTestSupport {

    /** JdbcTemplate поверх SQLite-файла dbFile (прямые слэши, чтобы Windows корректно распарсил путь). */
    fun jdbc(dbFile: Path): JdbcTemplate {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        return JdbcTemplate(ds)
    }

    /** Создаёт SessionStore поверх SQLite-файла dbFile (минимальная схема chat_messages). */
    fun store(dbFile: Path): SessionStore {
        val jdbc = jdbc(dbFile)
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages (session_id)")
        return SessionStore(jdbc)
    }
}
