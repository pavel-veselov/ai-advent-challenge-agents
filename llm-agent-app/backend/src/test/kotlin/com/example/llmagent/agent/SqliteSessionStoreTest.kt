package com.example.llmagent.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Path

/** SQLite-хранилище: порядок сообщений, переживание «перезапуска» и удаление сессии. */
class SqliteSessionStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    @Test
    fun `append then get returns messages in insertion order`() {
        val store = SqliteTestSupport.store(tmpDir.resolve("order.db"))
        store.append("sess-1", "user", "Первый")
        store.append("sess-1", "assistant", "Второй")
        store.append("sess-1", "user", "Третий")

        val history = store.get("sess-1")

        assertEquals(listOf("user", "assistant", "user"), history.map { it.role })
        assertEquals(listOf("Первый", "Второй", "Третий"), history.map { it.content })
    }

    @Test
    fun `messages survive store reinitialization on the same db file`() {
        val dbFile = tmpDir.resolve("persist.db")

        // «Первый запуск» backend
        val firstRun = SqliteTestSupport.store(dbFile)
        firstRun.append("sess-2", "user", "Сколько будет 2+2?")
        firstRun.append("sess-2", "assistant", "4")

        // «Перезапуск»: новое хранилище поверх того же SQLite-файла — данные должны сохраниться
        val secondRun = SqliteTestSupport.store(dbFile)
        val history = secondRun.get("sess-2")

        assertEquals(2, history.size)
        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertEquals(listOf("Сколько будет 2+2?", "4"), history.map { it.content })
    }

    @Test
    fun `delete removes only target session rows`() {
        val store = SqliteTestSupport.store(tmpDir.resolve("delete.db"))
        store.append("del-session", "user", "а")
        store.append("del-session", "assistant", "б")
        store.append("other-session", "user", "в")

        store.delete("del-session")

        assertTrue(store.get("del-session").isEmpty(), "история удалённой сессии должна опустеть")
        val other = store.get("other-session")
        assertEquals(1, other.size)
        assertEquals("в", other.first().content)

        // повторное удаление уже пустой сессии — без ошибок
        store.delete("del-session")
        assertTrue(store.get("del-session").isEmpty())
    }

    @Test
    fun `old schema without token columns is migrated so append with tokens works`() {
        val dbFile = tmpDir.resolve("migrate.db")

        // Создаём БД со СТАРОЙ схемой (без prompt_tokens/completion_tokens) напрямую через JDBC
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        val jdbc = JdbcTemplate(ds)
        jdbc.execute(
            """
            CREATE TABLE chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )

        // Инициализация SessionStore запускает миграцию (ALTER TABLE ... ADD COLUMN)
        val store = SessionStore(jdbc)
        store.append("sess-migrate", "user", "Привет", 12, null)
        store.append("sess-migrate", "assistant", "Ответ", 5, 7)

        val history = store.get("sess-migrate")
        assertEquals(2, history.size)
        assertEquals(12, history[0].promptTokens)
        assertEquals(null, history[0].completionTokens)
        assertEquals(5, history[1].promptTokens)
        assertEquals(7, history[1].completionTokens)
    }

    @Test
    fun `listSessionAggregates returns per-session token aggregates ordered by most recent`() {
        val dbFile = tmpDir.resolve("aggregates.db")
        val jdbc = projSchemaJdbc(dbFile)
        jdbc.update("INSERT INTO projects (id, name) VALUES (1, 'проект')")
        jdbc.update("INSERT INTO chat_sessions (session_id, project_id, title) VALUES ('sess-a', 1, 'A')")
        jdbc.update("INSERT INTO chat_sessions (session_id, project_id, title) VALUES ('sess-b', 1, 'B')")
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens, created_at) VALUES ('sess-a','user','запрос',10,NULL,'2026-09-01 10:00:00')")
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens, created_at) VALUES ('sess-a','assistant','ответ',8,20,'2026-09-01 10:00:01')")
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens, created_at) VALUES ('sess-b','user','другой запрос',3,NULL,'2026-09-02 10:00:00')")

        val store = SessionStore(jdbc)
        val aggregates = store.listSessionAggregates()

        assertEquals(2, aggregates.size)
        // самая свежая последняя активность — первой (ORDER BY lastActivity DESC)
        assertEquals("sess-b", aggregates[0].sessionId)
        assertEquals(1L, aggregates[0].messageCount)
        assertEquals(3L, aggregates[0].promptTokens)
        assertEquals(0L, aggregates[0].completionTokens)
        assertEquals(1L, aggregates[0].projectId)

        assertEquals("sess-a", aggregates[1].sessionId)
        assertEquals(2L, aggregates[1].messageCount)
        assertEquals(18L, aggregates[1].promptTokens)
        assertEquals(20L, aggregates[1].completionTokens)
        assertTrue(aggregates[1].lastActivity.isNotBlank(), "lastActivity не должна быть пустой")
        assertEquals(1L, aggregates[1].projectId)
    }

    @Test
    fun `listSessionAggregates returns empty list for empty database`() {
        val store = SqliteTestSupport.store(tmpDir.resolve("empty.db"))
        assertTrue(store.listSessionAggregates().isEmpty())
    }

    @Test
    fun `listSessionAggregates returns the FIRST user message as firstUserMessage`() {
        val store = SqliteTestSupport.store(tmpDir.resolve("first-user.db"))
        store.append("sess-first", "user", "первый запрос")
        store.append("sess-first", "assistant", "первый ответ")
        store.append("sess-first", "user", "второй запрос")
        store.append("sess-first", "assistant", "второй ответ")

        val aggregate = store.listSessionAggregates().single()

        assertEquals("первый запрос", aggregate.firstUserMessage)
    }

    @Test
    fun `listSessionAggregates returns null firstUserMessage when session has no user messages`() {
        val store = SqliteTestSupport.store(tmpDir.resolve("no-user.db"))
        store.append("sess-no-user", "assistant", "сообщение без user")

        val aggregate = store.listSessionAggregates().single()

        assertEquals(null, aggregate.firstUserMessage)
    }

    // ------------------------------------------------------------------
    // Day-12: реестр сессий (chat_sessions) поверх проектов
    // ------------------------------------------------------------------

    @Test
    fun `createSession returns a server id and stores project and title`() {
        val dbFile = tmpDir.resolve("create-session.db")
        val jdbc = projSchemaJdbc(dbFile)
        jdbc.update("INSERT INTO projects (id, name) VALUES (1, 'проект')")
        val store = SessionStore(jdbc)

        val sessionId = store.createSession(1L, "первый чат")
        assertTrue(sessionId.isNotBlank() && sessionId != "unknown", "id должен генерироваться сервером")

        assertEquals(1L, store.getProjectId(sessionId))
        assertEquals("первый чат", store.titleOf(sessionId))
    }

    @Test
    fun `getProjectId and titleOf return null for unknown session`() {
        val store = SqliteTestSupport.store(tmpDir.resolve("unknown-session.db"))
        // без chat_sessions (юнит-схема) и без строки — fail-open null, не исключение
        assertEquals(null, store.getProjectId("never-existed"))
        assertEquals(null, store.titleOf("never-existed"))
    }

    @Test
    fun `listByProject returns only project sessions with aggregates and empty sessions`() {
        val dbFile = tmpDir.resolve("list-by-project.db")
        val jdbc = projSchemaJdbc(dbFile)
        jdbc.update("INSERT INTO projects (id, name) VALUES (1, 'проект-1')")
        jdbc.update("INSERT INTO projects (id, name) VALUES (2, 'проект-2')")
        jdbc.update("INSERT INTO chat_sessions (session_id, project_id, title) VALUES ('p1-empty', 1, 'пустая')")
        jdbc.update("INSERT INTO chat_sessions (session_id, project_id, title) VALUES ('p1-active', 1, 'активная')")
        jdbc.update("INSERT INTO chat_sessions (session_id, project_id, title) VALUES ('p2-s', 2, 'другой проект')")
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES ('p1-active','user','запрос',10,NULL)")
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES ('p1-active','assistant','ответ',8,20)")
        val store = SessionStore(jdbc)

        val sessions = store.listByProject(1L)

        assertEquals(2, sessions.size, "в проект-1 входят и пустая, и активная сессии")
        // активная свежее (последнее сообщение) — первой
        assertEquals("p1-active", sessions[0].sessionId)
        assertEquals("активная", sessions[0].title)
        assertEquals(2L, sessions[0].messageCount)
        assertEquals(18L, sessions[0].promptTokens)
        assertEquals(20L, sessions[0].completionTokens)
        assertEquals("запрос", sessions[0].firstUserMessage)
        assertEquals(1L, sessions[0].projectId)
        // пустая сессия — messageCount 0, проект тот же
        assertEquals("p1-empty", sessions[1].sessionId)
        assertEquals(0L, sessions[1].messageCount)
        assertEquals(1L, sessions[1].projectId)
        // чужая сессия проекта-2 в список не входит
        assertTrue(sessions.none { it.sessionId == "p2-s" })
        assertEquals(0, store.listByProject(42L).size, "несуществующий проект — пустой список")
    }

    @Test
    fun `listSessionAggregates includes empty sessions from chat_sessions`() {
        val dbFile = tmpDir.resolve("agg-empty.db")
        val jdbc = projSchemaJdbc(dbFile)
        jdbc.update("INSERT INTO projects (id, name) VALUES (1, 'проект')")
        jdbc.update("INSERT INTO chat_sessions (session_id, project_id, title) VALUES ('empty-1', 1, '')")
        jdbc.update("INSERT INTO chat_sessions (session_id, project_id, title) VALUES ('active-1', 1, '')")
        jdbc.update("INSERT INTO chat_messages (session_id, role, content) VALUES ('active-1','user','привет')")
        val store = SessionStore(jdbc)

        val aggregates = store.listSessionAggregates()

        assertEquals(2, aggregates.size)
        val empty = aggregates.first { it.sessionId == "empty-1" }
        assertEquals(0L, empty.messageCount, "пустая сессия из chat_sessions видна с messageCount=0")
        assertEquals(1L, empty.projectId)
    }

    @Test
    fun `delete removes chat_sessions row as well as messages`() {
        val dbFile = tmpDir.resolve("delete-session-row.db")
        val jdbc = projSchemaJdbc(dbFile)
        jdbc.update("INSERT INTO projects (id, name) VALUES (1, 'проект')")
        val store = SessionStore(jdbc)
        val sessionId = store.createSession(1L, "сессия")
        store.append(sessionId, "user", "привет")

        store.delete(sessionId)

        assertEquals(null, store.getProjectId(sessionId), "строка chat_sessions удалена вместе с историей")
        assertTrue(store.get(sessionId).isEmpty())
    }

    @Test
    fun `lifetime stats survive session deletion`() {
        val store = SqliteTestSupport.store(tmpDir.resolve("lifetime.db"))
        store.append("lt-s1", "user", "привет", 100, null)
        store.append("lt-s1", "assistant", "ответ", 50, 200)
        store.addLifetimeTokens(50, 200, 0.00003)
        store.append("lt-s2", "user", "вопрос", 25, null)

        // удаление сессий не уменьшает кумулятивные счётчики «за всё время»
        store.delete("lt-s1")
        store.delete("lt-s2")

        val lifetime = store.getLifetimeStats()
        assertEquals(2L, lifetime.sessionsTotal, "счётчик сессий должен пережить удаление")
        // в lifetime попадают только финализированные assistant-сообщения (addLifetimeTokens)
        assertEquals(50L, lifetime.promptTokensTotal)
        assertEquals(200L, lifetime.completionTokensTotal)
        assertTrue(store.listSessionAggregates().isEmpty(), "текущих сессий не должно остаться")
    }

    @Test
    fun `lifetime stats survive store reinitialization on the same db file`() {
        val dbFile = tmpDir.resolve("lt-persist.db")

        val firstRun = SqliteTestSupport.store(dbFile)
        firstRun.append("lt-p1", "user", "а", 10, null)
        firstRun.append("lt-p1", "assistant", "б", 5, 20)
        firstRun.addLifetimeTokens(5, 20, 0.0)

        val secondRun = SqliteTestSupport.store(dbFile)
        val lifetime = secondRun.getLifetimeStats()
        assertEquals(1L, lifetime.sessionsTotal)
        assertEquals(5L, lifetime.promptTokensTotal)
        assertEquals(20L, lifetime.completionTokensTotal)
    }

    @Test
    fun `lifetime stats are backfilled from existing history on first store init and survive deletion`() {
        val dbFile = tmpDir.resolve("lt-backfill.db")
        val jdbc = oldSchemaJdbc(dbFile)
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES (?,?,?,?,?)", "old-1", "user", "запрос", 100, null)
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES (?,?,?,?,?)", "old-1", "assistant", "ответ 1", 40, 60)
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES (?,?,?,?,?)", "old-2", "user", "вопрос", 25, null)
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES (?,?,?,?,?)", "old-2", "assistant", "ответ 2", 30, 50)

        // Инициализация SessionStore мигрирует схему и бэкафиллит lifetime_stats из истории
        val store = SessionStore(jdbc)
        val seeded = store.getLifetimeStats()
        assertEquals(2L, seeded.sessionsTotal)
        assertEquals(195L, seeded.promptTokensTotal) // вся колонка prompt_tokens: 100+40+25+30
        assertEquals(110L, seeded.completionTokensTotal) // 60+50
        assertTrue(seeded.costUsdTotal > 0.0, "стоимость должна бэкафиллиться из токенов")

        // удаление всей истории не теряет счётчики «за всё время»
        store.delete("old-1")
        store.delete("old-2")
        assertEquals(2L, store.getLifetimeStats().sessionsTotal)
        assertEquals(195L, store.getLifetimeStats().promptTokensTotal)
        assertEquals(110L, store.getLifetimeStats().completionTokensTotal)
    }

    @Test
    fun `lifetime stats are not double counted on store reinitialization`() {
        val dbFile = tmpDir.resolve("lt-idem.db")
        val jdbc = oldSchemaJdbc(dbFile)
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES (?,?,?,?,?)", "old-1", "assistant", "отв", 70, 110)

        // первый init: строка-счётчик создаётся и бэкафиллится из истории
        val firstRun = SessionStore(jdbc)
        assertEquals(70L, firstRun.getLifetimeStats().promptTokensTotal)

        // новая активность между «рестартами»
        firstRun.append("new-1", "user", "новый", 5, null)
        firstRun.append("new-1", "assistant", "ответ", 7, 9)
        firstRun.addLifetimeTokens(7, 9, 0.0)

        // повторный init НЕ пересчитывает счётчики из истории — нет двойного учёта
        val secondRun = SessionStore(jdbc)
        val lifetime = secondRun.getLifetimeStats()
        assertEquals(2L, lifetime.sessionsTotal) // 1 (бэкафилл) + 1 (новая сессия)
        assertEquals(77L, lifetime.promptTokensTotal) // 70 + 7, а не 82 (иначе повторный SUM истории: 70+5+7)
        assertEquals(119L, lifetime.completionTokensTotal) // 110 + 9
    }

    @Test
    fun `lifetime stats backfill seeds an already-empty counter row`() {
        // Кейс из live-пробы: история уже есть, а строка-счётчик была создана
        // промежуточным релизом и осталась в нулях — её надо дозаполнить один раз.
        val dbFile = tmpDir.resolve("lt-heal.db")
        val jdbc = oldSchemaJdbc(dbFile)
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, prompt_tokens, completion_tokens) VALUES (?,?,?,?,?)", "old-1", "assistant", "отв", 396000, 5000)
        jdbc.execute(
            """
            CREATE TABLE lifetime_stats (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                sessions_total INTEGER NOT NULL DEFAULT 0,
                prompt_tokens_total INTEGER NOT NULL DEFAULT 0,
                completion_tokens_total INTEGER NOT NULL DEFAULT 0,
                cost_usd_total REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        jdbc.update("INSERT OR IGNORE INTO lifetime_stats (id) VALUES (1)")

        val store = SessionStore(jdbc)
        val lifetime = store.getLifetimeStats()
        assertEquals(1L, lifetime.sessionsTotal)
        assertEquals(396000L, lifetime.promptTokensTotal)
        assertEquals(5000L, lifetime.completionTokensTotal)
        assertTrue(lifetime.costUsdTotal > 0.0, "стоимость должна быть посчитана из токенов")
    }

    /**
     * JDBC поверх «проектной» схемы: chat_messages (с токенами/parent_id) + projects +
     * chat_sessions — та же форма, что schema.sql.
     */
    private fun projSchemaJdbc(dbFile: Path): JdbcTemplate {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        val jdbc = JdbcTemplate(ds)
        jdbc.execute(
            """
            CREATE TABLE chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                prompt_tokens INTEGER,
                completion_tokens INTEGER,
                parent_id INTEGER,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                updated_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        jdbc.execute(
            """
            CREATE TABLE chat_sessions (
                session_id TEXT PRIMARY KEY,
                project_id INTEGER NOT NULL REFERENCES projects(id),
                title TEXT,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        return jdbc
    }

    /** JDBC поверх «старой» схемы: только chat_messages с токенами, без lifetime_stats. */
    private fun oldSchemaJdbc(dbFile: Path): JdbcTemplate {
        val ds = DriverManagerDataSource()
        ds.setDriverClassName("org.sqlite.JDBC")
        ds.url = "jdbc:sqlite:${dbFile.toAbsolutePath().toString().replace('\\', '/')}"
        val jdbc = JdbcTemplate(ds)
        jdbc.execute(
            """
            CREATE TABLE chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                prompt_tokens INTEGER,
                completion_tokens INTEGER,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """.trimIndent()
        )
        return jdbc
    }
}
