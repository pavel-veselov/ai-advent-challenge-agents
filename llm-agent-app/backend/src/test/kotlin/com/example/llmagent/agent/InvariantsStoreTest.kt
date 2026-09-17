package com.example.llmagent.agent

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Юнит-тесты инвариантов агента ПО ПРОЕКТУ: дефолт, добавление, обновление, удаление,
 *  обрезка текста, счётчик, удаление по проекту (каскад), нормализация пустой категории. */
class InvariantsStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): InvariantsStore =
        InvariantsStore(SqliteTestSupport.jdbc(tmpDir.resolve("inv-${UUID.randomUUID()}.db")))

    @Test
    fun `list returns empty invariants for missing project`() {
        assertTrue(store().list("p1").isEmpty(), "у проекта без инвариантов — пустой список")
    }

    @Test
    fun `add creates invariant and list reflects it`() {
        val f = store()
        val created = f.add("p1", "Ограничения по стеку", "Использовать только PostgreSQL, не MongoDB")
        assertNotNull(created, "add возвращает созданную запись")
        assertTrue(created!!.id > 0, "запись получила реальный id из БД")
        assertEquals("p1", created.projectId)
        assertEquals("Ограничения по стеку", created.category)
        assertEquals("Использовать только PostgreSQL, не MongoDB", created.text)
        assertTrue(created.createdAt.isNotBlank() && created.updatedAt.isNotBlank())

        val list = f.list("p1")
        assertEquals(1, list.size)
        assertEquals(created.id, list[0].id)
    }

    @Test
    fun `add with blank text returns null`() {
        assertNull(store().add("p1", null, ""), "пустой текст — null (fail-open)")
        assertNull(store().add("p1", null, "   "), "blank текст — null")
    }

    @Test
    fun `add normalizes blank category to null`() {
        val created = store().add("p1", "   ", "текст")
        assertNotNull(created)
        assertNull(created!!.category, "blank категория сохраняется как null")
    }

    @Test
    fun `update modifies category and text preserving created_at`() {
        val f = store()
        val created = f.add("p1", "Стек", "первый текст")!!
        val originalCreatedAt = created.createdAt

        val updated = f.update(created.id, "Архитектура", "второй текст")
        assertNotNull(updated)
        assertEquals(created.id, updated!!.id)
        assertEquals("Архитектура", updated.category)
        assertEquals("второй текст", updated.text)
        assertEquals(originalCreatedAt, updated.createdAt, "created_at не меняется при обновлении")
        assertEquals(1, f.list("p1").size, "обновление не плодит дублей")
    }

    @Test
    fun `update returns null for nonexistent id`() {
        assertNull(store().update(12345, null, "текст"), "нет записи — null")
    }

    @Test
    fun `update with blank text returns null`() {
        val f = store()
        val created = f.add("p1", null, "текст")!!
        assertNull(f.update(created.id, null, ""), "blank текст в update — null")
        assertNull(f.update(created.id, null, "   "))
        // исходная запись не тронута
        assertEquals("текст", f.list("p1")[0].text)
    }

    @Test
    fun `delete returns true then false on second delete`() {
        val f = store()
        val created = f.add("p1", null, "удаляемый")!!
        assertTrue(f.delete(created.id), "первое удаление успешно")
        assertFalse(f.delete(created.id), "повторное удаление — false (нет записи)")
        assertTrue(f.list("p1").isEmpty())
    }

    @Test
    fun `add and update truncate text to 2000 chars`() {
        val f = store()
        val longText = "ж".repeat(2500)
        val created = f.add("p1", null, longText)!!
        assertEquals(2000, created.text.length, "текст обрезан до 2000 символов")

        val updated = f.update(created.id, null, longText)!!
        assertEquals(2000, updated.text.length)
    }

    @Test
    fun `count returns number of invariants and 0 on missing project`() {
        val f = store()
        assertEquals(0, f.count("p1"))
        f.add("p1", null, "один")
        f.add("p1", null, "два")
        assertEquals(2, f.count("p1"))
        assertEquals(0, f.count("p2"), "чужой проект — 0")
    }

    @Test
    fun `deleteByProject removes only that project invariants`() {
        val f = store()
        f.add("p1", null, "инвариант 1")
        f.add("p1", null, "инвариант 2")
        f.add("p2", null, "инвариант чужого проекта")

        f.deleteByProject("p1")
        assertTrue(f.list("p1").isEmpty(), "инварианты p1 удалены каскадом")
        assertEquals(1, f.list("p2").size, "чужой проект не тронут")
        assertEquals(1, f.count("p2"))
    }

    @Test
    fun `invariants are scoped per project`() {
        val f = store()
        f.add("p1", null, "только для p1")
        f.add("p2", null, "только для p2")
        assertEquals(1, f.list("p1").size)
        assertEquals(1, f.list("p2").size)
        assertEquals("только для p1", f.list("p1")[0].text)
        assertEquals("только для p2", f.list("p2")[0].text)
    }
}
