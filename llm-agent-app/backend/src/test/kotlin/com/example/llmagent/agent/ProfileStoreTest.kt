package com.example.llmagent.agent

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты справочника профилей пользователя: create (все поля), list (ORDER BY id),
 * findById/findByName, update, delete, уникальность name (id = -1), лимиты длины полей,
 * персистентность между «перезапусками» store на том же файле БД.
 */
class ProfileStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): ProfileStore =
        ProfileStore(SqliteTestSupport.jdbc(tmpDir.resolve("profiles-${UUID.randomUUID()}.db")))

    @Test
    fun `create inserts new profile with all fields`() {
        val f = store()
        val p = f.create(
            "Профиль 1",
            "Backend-разработчик",
            "кратко, списком",
            "код объяснять по шагам",
            "отвечай только по-русски",
        )
        assertTrue(p.id > 0, "созданный профиль получил AUTOINCREMENT id")
        assertEquals("Профиль 1", p.name)
        assertEquals("Backend-разработчик", p.position)
        assertEquals("кратко, списком", p.responseFormat)
        assertEquals("код объяснять по шагам", p.preferences)
        assertEquals("отвечай только по-русски", p.constraints)
        assertTrue(p.createdAt.isNotBlank())
        assertTrue(p.updatedAt.isNotBlank())
        assertEquals(1, f.count())
    }

    @Test
    fun `create allows null optional fields`() {
        val f = store()
        val p = f.create("Только имя", null, null, null, null)
        assertTrue(p.id > 0)
        assertEquals("Только имя", p.name)
        assertEquals(null, p.position)
        assertEquals(null, p.responseFormat)
        assertEquals(null, p.preferences)
        assertEquals(null, p.constraints)
    }

    @Test
    fun `create duplicate name fails open with id -1`() {
        val f = store()
        val first = f.create("Профиль 1", "аналитик", null, null, null)
        assertTrue(first.id > 0)
        val second = f.create("Профиль 1", "тестировщик", null, null, null)
        assertEquals(-1L, second.id, "дубль имени (UNIQUE) не создаёт строку — id = -1")
        assertEquals(1, f.count(), "строка по-прежнему одна")
    }

    @Test
    fun `list orders by id and returns all profiles`() {
        val f = store()
        f.create("второй", null, null, null, null)
        f.create("первый", null, null, null, null)
        f.create("третий", null, null, null, null)

        assertEquals(listOf("второй", "первый", "третий"), f.list().map { it.name }, "порядок — по id (созданию)")
    }

    @Test
    fun `findById and findByName return existing profile or null`() {
        val f = store()
        val created = f.create("поиск", "лид", null, null, null)

        assertEquals(created.id, f.findById(created.id)?.id)
        assertEquals("поиск", f.findByName("поиск")?.name)
        assertEquals(null, f.findById(999999), "несуществующий id — null")
        assertEquals(null, f.findByName("нет такого"), "несуществующее имя — null")
    }

    @Test
    fun `update changes fields keeping id and created_at`() {
        val f = store()
        val created = f.create("до", "старая должность", "старый формат", null, null)

        assertTrue(f.update(created.id, "после", "новая должность", null, "лимит", "новое ограничение"))
        val updated = f.findById(created.id)!!
        assertEquals(created.id, updated.id)
        assertEquals("после", updated.name)
        assertEquals("новая должность", updated.position)
        assertEquals(null, updated.responseFormat, "null в update очищает поле")
        assertEquals("лимит", updated.preferences)
        assertEquals("новое ограничение", updated.constraints)
        assertEquals(created.createdAt, updated.createdAt, "created_at сохраняется при обновлении")
        assertEquals(1, f.count())
    }

    @Test
    fun `update returns false for missing id or foreign duplicate name`() {
        val f = store()
        val a = f.create("А", null, null, null, null)
        f.create("Б", null, null, null, null)

        assertFalse(f.update(999999, "никто", null, null, null, null), "нет такого id — false")
        assertFalse(
            f.update(a.id, "Б", null, null, null, null),
            "имя занято ДРУГИМ профилем — UNIQUE нарушен, false",
        )
        // после неудачных update данные не изменились
        assertEquals("А", f.findById(a.id)!!.name)
        assertEquals(2, f.count())
    }

    @Test
    fun `delete returns true for existing and false for missing`() {
        val f = store()
        val p = f.create("удаляемый", null, null, null, null)
        assertTrue(f.delete(p.id), "существующий профиль удаляется")
        assertFalse(f.delete(p.id), "повторное удаление — false")
        assertFalse(f.delete(999999), "несуществующий id — false")
        assertEquals(0, f.count())
        assertEquals(null, f.findById(p.id))
    }

    @Test
    fun `text fields truncated to limits`() {
        val f = store()
        val p = f.create("и".repeat(250), "д".repeat(2500), "ф".repeat(2500), "п".repeat(2500), "о".repeat(2500))
        assertEquals(120, p.name.length, "name обрезан до 120 символов")
        assertEquals(2000, p.position!!.length, "position обрезан до 2000 символов")
        assertEquals(2000, p.responseFormat!!.length)
        assertEquals(2000, p.preferences!!.length)
        assertEquals(2000, p.constraints!!.length)
        assertEquals(2000, f.findById(p.id)!!.preferences!!.length, "в БД тоже обрезанное значение")
    }

    @Test
    fun `profiles persist across store restart on the same db file`() {
        val dbFile = tmpDir.resolve("profile-persist-${UUID.randomUUID()}.db")
        val first = ProfileStore(SqliteTestSupport.jdbc(dbFile))
        val created = first.create("живучий", "лид", null, null, null)

        // «Перезапуск»: новый store поверх того же файла БД (схема CREATE IF NOT EXISTS)
        val second = ProfileStore(SqliteTestSupport.jdbc(dbFile))
        val reread = second.findById(created.id)
        assertTrue(reread != null, "профиль переживает перезапуск хранилища")
        assertEquals("живучий", reread!!.name)
        assertEquals("лид", reread.position)
        assertEquals(1, second.count())
    }

    @Test
    fun `count reflects stored profiles`() {
        val f = store()
        assertEquals(0, f.count())
        f.create("один", null, null, null, null)
        f.create("два", null, null, null, null)
        assertEquals(2, f.count())
        assertTrue(f.delete(f.findByName("один")!!.id))
        assertEquals(1, f.count())
        assertTrue(f.findById(424242) == null)
        assertTrue(f.findByName("один") == null)
        assertTrue(f.list().none { it.id == -1L })
    }
}
