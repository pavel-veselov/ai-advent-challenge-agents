package com.example.llmagent.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
}
