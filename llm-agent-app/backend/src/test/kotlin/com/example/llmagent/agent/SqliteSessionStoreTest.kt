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
        val store = SqliteTestSupport.store(tmpDir.resolve("aggregates.db"))
        store.append("sess-a", "user", "запрос", 10, null)
        store.append("sess-a", "assistant", "ответ", 8, 20)
        store.append("sess-b", "user", "другой запрос", 3, null)

        val aggregates = store.listSessionAggregates()

        assertEquals(2, aggregates.size)
        // последняя созданная сессия — первая (ORDER BY MAX(id) DESC)
        assertEquals("sess-b", aggregates[0].sessionId)
        assertEquals(1L, aggregates[0].messageCount)
        assertEquals(3L, aggregates[0].promptTokens)
        assertEquals(0L, aggregates[0].completionTokens)

        assertEquals("sess-a", aggregates[1].sessionId)
        assertEquals(2L, aggregates[1].messageCount)
        assertEquals(18L, aggregates[1].promptTokens)
        assertEquals(20L, aggregates[1].completionTokens)
        assertTrue(aggregates[1].lastActivity.isNotBlank(), "lastActivity не должна быть пустой")
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
