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

    /** Создаёт минимальную схему chat_messages (той же формы, что schema.sql) на [jdbc]. */
    fun createChatMessagesTable(jdbc: JdbcTemplate) {
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
    }

    /** Создаёт SessionStore поверх SQLite-файла dbFile (минимальная схема chat_messages). */
    fun store(dbFile: Path): SessionStore {
        val jdbc = jdbc(dbFile)
        createChatMessagesTable(jdbc)
        return SessionStore(jdbc)
    }

    /**
     * SessionStore поверх SQLite-файла dbFile с ПОДКЛЮЧЁННЫМИ ветками: дерево parent_id
     * в chat_messages поддерживается самим append (см. SessionStore), активную ветку
     * знает возвращаемый SessionBranchStore. Имя файла dbFile должно быть уникальным
     * для каждого набора (ветки/стратегии персистятся в той же БД).
     */
    fun branchingStore(dbFile: Path): Pair<SessionStore, SessionBranchStore> {
        val jdbc = jdbc(dbFile)
        createChatMessagesTable(jdbc)
        val branchStore = SessionBranchStore(jdbc)
        return SessionStore(jdbc, branchStore = branchStore) to branchStore
    }
}
