package com.example.llmagent.agent

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Юнит-тесты ProjectStore (таблица projects + реестр chat_sessions): CRUD, порядок, fail-open. */
class ProjectStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun jdbc(): org.springframework.jdbc.core.JdbcTemplate =
        SqliteTestSupport.jdbc(tmpDir.resolve("proj-${UUID.randomUUID()}.db"))

    @Test
    fun `create returns growing ids and assigns created and updated timestamps`() {
        val store = ProjectStore(jdbc())
        val id1 = store.create("первый")
        val id2 = store.create("второй")
        assertTrue(id1 > 0, "первый id должен быть положительным")
        assertTrue(id2 > id1, "id должны расти")

        val p1 = store.get(id1)!!
        assertEquals("первый", p1.name)
        assertTrue(p1.createdAt.isNotBlank(), "createdAt заполняется")
        assertTrue(p1.updatedAt.isNotBlank(), "updatedAt заполняется")
    }

    @Test
    fun `list returns projects in creation order`() {
        val store = ProjectStore(jdbc())
        val a = store.create("Алиса")
        val b = store.create("Боб")
        val ids = store.list().map { it.id }
        assertEquals(listOf(a, b), ids, "порядок — по созданию")
        assertEquals(listOf("Алиса", "Боб"), store.list().map { it.name })
    }

    @Test
    fun `get returns null and exists false for missing project`() {
        val store = ProjectStore(jdbc())
        assertNull(store.get(42L))
        assertFalse(store.exists(42L))
    }

    @Test
    fun `updateName renames project and returns true, false for missing`() {
        val store = ProjectStore(jdbc())
        val id = store.create("старое имя")
        assertTrue(store.updateName(id, "новое имя"))
        assertEquals("новое имя", store.get(id)!!.name)

        assertFalse(store.updateName(999L, "несуществующий"), "чужой проект не переименовывается")
    }

    @Test
    fun `delete removes project and returns false for missing`() {
        val store = ProjectStore(jdbc())
        val id = store.create("удаляемый")
        assertTrue(store.exists(id))

        assertTrue(store.delete(id))
        assertFalse(store.exists(id))
        assertNull(store.get(id))

        assertFalse(store.delete(id), "повторное удаление — false")
    }

    @Test
    fun `fail open when store table is missing`() {
        val jdbc = jdbc()
        val store = ProjectStore(jdbc)
        // «битое» хранилище: таблица удалена — операции должны вернуть дефолты, не бросать
        jdbc.execute("DROP TABLE projects")

        assertTrue(store.list().isEmpty(), "list при сбое — пустой список")
        assertNull(store.get(1L), "get при сбое — null")
        assertFalse(store.exists(1L), "exists при сбое — false")
        assertFalse(store.delete(1L), "delete при сбое — false")
        assertFalse(store.updateName(1L, "x"), "updateName при сбое — false")
        assertEquals(-1L, store.create("x"), "create при сбое — -1")
    }
}
