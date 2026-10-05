package com.example.llmagent.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Юнит-тесты хранилища памяти задачи (Day-25, task_memory):
 * - roundtrip upsert/get (списки — JSON-массивы строк);
 * - неизвестная сессия → пустая структура (GET никогда не 404);
 * - upsert — ПОЛНАЯ замена состояния (не слияние);
 * - fail-open: битые/не-строковые JSON-массивы в БД разбираются как пустые списки;
 * - цель обрезается до 2000 символов.
 * SQLite — временный файл.
 */
class JdbcTaskMemoryStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun newStore(name: String): JdbcTaskMemoryStore =
        JdbcTaskMemoryStore(SqliteTestSupport.jdbc(tmpDir.resolve(name)))

    @Test
    fun `upsert and get roundtrip`() {
        val store = newStore("tm-roundtrip.db")
        assertNull(store.get("tm-1").updatedAt, "у пустого состояния нет updated_at")

        val saved = store.upsert(
            "tm-1",
            "Собрать отчёт",
            listOf("формат JSON", "язык — русский"),
            listOf("не использовать БД"),
        )
        assertNotNull(saved)
        assertEquals("tm-1", saved!!.sessionId)
        assertEquals("Собрать отчёт", saved.goal)
        assertEquals(listOf("формат JSON", "язык — русский"), saved.clarifications)
        assertNotNull(saved.updatedAt)

        val loaded = store.get("tm-1")
        assertEquals("Собрать отчёт", loaded.goal)
        assertEquals(listOf("формат JSON", "язык — русский"), loaded.clarifications)
        assertEquals(listOf("не использовать БД"), loaded.constraints)
        assertEquals(saved.updatedAt, loaded.updatedAt)
    }

    @Test
    fun `unknown session returns empty memory`() {
        val store = newStore("tm-empty.db")
        val memory = store.get("tm-none")
        assertEquals("tm-none", memory.sessionId)
        assertEquals("", memory.goal)
        assertTrue(memory.clarifications.isEmpty())
        assertTrue(memory.constraints.isEmpty())
        assertNull(memory.updatedAt)
        assertTrue(memory.isEmpty())
    }

    @Test
    fun `upsert fully replaces previous state`() {
        val store = newStore("tm-replace.db")
        store.upsert("tm-2", "Старая цель", listOf("старое уточнение"), listOf("старое ограничение"))
        store.upsert("tm-2", "Новая цель", emptyList(), listOf("новое ограничение"))

        val memory = store.get("tm-2")
        assertEquals("Новая цель", memory.goal)
        assertTrue(memory.clarifications.isEmpty(), "полная замена: старые уточнения не остаются")
        assertEquals(listOf("новое ограничение"), memory.constraints)
    }

    @Test
    fun `corrupt or non-string json lists in db parse as empty lists`() {
        val store = newStore("tm-corrupt.db")
        val jdbc = SqliteTestSupport.jdbc(tmpDir.resolve("tm-corrupt.db"))
        jdbc.update(
            "INSERT INTO task_memory (session_id, goal, clarifications, constraints, updated_at) VALUES (?, ?, ?, ?, ?)",
            "tm-bad", "цель", "{не json", "[1, 2]", null,
        )

        val memory = store.get("tm-bad")
        assertEquals("цель", memory.goal)
        assertTrue(memory.clarifications.isEmpty(), "битый JSON — пустой список (fail-open)")
        assertTrue(memory.constraints.isEmpty(), "не-строковый JSON-массив — пустой список (fail-open)")
    }

    @Test
    fun `goal longer than limit is truncated`() {
        val store = newStore("tm-truncate.db")
        store.upsert("tm-3", "х".repeat(2500), emptyList(), emptyList())
        assertEquals(2000, store.get("tm-3").goal.length)
    }
}
