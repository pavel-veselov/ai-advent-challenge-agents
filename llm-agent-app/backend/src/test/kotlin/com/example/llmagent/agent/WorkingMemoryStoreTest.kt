package com.example.llmagent.agent

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Юнит-тесты рабочей памяти агента ПО ПРОЕКТУ: дефолт, задача, заметки (лимит 1000), clear, удаление. */
class WorkingMemoryStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): WorkingMemoryStore =
        WorkingMemoryStore(SqliteTestSupport.jdbc(tmpDir.resolve("wm-${UUID.randomUUID()}.db")))

    @Test
    fun `get returns empty memory for missing project`() {
        val wm = store().get("p1")
        assertNull(wm.task, "задача отсутствует — null")
        assertTrue(wm.notes.isEmpty(), "заметок нет — пустой список")
    }

    @Test
    fun `setTask creates and updates task keeping notes`() {
        val f = store()
        f.setTask("p1", "разобраться с багом")
        assertEquals("разобраться с багом", f.get("p1").task)

        f.appendNote("p1", "заметка")
        f.setTask("p1", "новая задача")
        val wm = f.get("p1")
        assertEquals("новая задача", wm.task, "задача перезаписана")
        assertEquals(listOf("заметка"), wm.notes, "заметки при setTask не тронуты")

        // task = null сбрасывает задачу
        f.setTask("p1", null)
        assertNull(f.get("p1").task)
        assertEquals(listOf("заметка"), f.get("p1").notes)
    }

    @Test
    fun `appendNote appends notes in order`() {
        val f = store()
        f.appendNote("p1", "первая")
        f.appendNote("p1", "вторая: с \"кавычками\" и\nпереносом")
        assertEquals(
            listOf("первая", "вторая: с \"кавычками\" и\nпереносом"),
            f.get("p1").notes,
            "порядок вставки сохраняется, спецсимволы JSON экранируются корректно",
        )
    }

    @Test
    fun `appendNote truncates note to 1000 chars`() {
        val f = store()
        val longNote = "ж".repeat(1500)
        f.appendNote("p1", longNote)
        val notes = f.get("p1").notes
        assertEquals(1, notes.size)
        assertEquals(1000, notes[0].length, "заметка обрезана до 1000 символов")
    }

    @Test
    fun `clear resets task and notes but keeps row`() {
        val f = store()
        f.setTask("p1", "задача")
        f.appendNote("p1", "заметка")
        f.clear("p1")
        val wm = f.get("p1")
        assertNull(wm.task)
        assertTrue(wm.notes.isEmpty())
        // после очистки можно снова работать с тем же проектом
        f.appendNote("p1", "заново")
        assertEquals(listOf("заново"), f.get("p1").notes)
    }

    @Test
    fun `deleteByProject removes row and other projects untouched`() {
        val f = store()
        f.setTask("p1", "задача 1")
        f.appendNote("p1", "заметка 1")
        f.setTask("p2", "задача 2")
        f.deleteByProject("p1")
        assertNull(f.get("p1").task, "память p1 удалена — дефолтная структура")
        assertTrue(f.get("p1").notes.isEmpty())
        assertEquals("задача 2", f.get("p2").task, "чужой проект не тронут")
    }

    @Test
    fun `legacy session keyed schema is reset to project keyed`() {
        // Старый файл БД: agent_working_memory с session_id-PK и per-session данными.
        // Миграция (по решению пользователя old-сессии удаляются) должна сбросить таблицу
        // под project_id-PK и стереть старые строки.
        val dbFile = tmpDir.resolve("wm-legacy-${UUID.randomUUID()}.db")
        val jdbc = SqliteTestSupport.jdbc(dbFile)
        jdbc.execute(
            """
            CREATE TABLE agent_working_memory (
                session_id TEXT PRIMARY KEY,
                task       TEXT,
                notes      TEXT NOT NULL DEFAULT '[]',
                updated_at TEXT
            )
            """.trimIndent()
        )
        jdbc.update("INSERT INTO agent_working_memory (session_id, task, notes) VALUES ('old-s1', 'задача', '[]')")

        val wm = WorkingMemoryStore(jdbc)
        // старые per-session строки удалены: чтение по старому ключу — пустая структура
        assertNull(wm.get("old-s1").task, "легаси-память удалена миграцией")
        // новое хранилище работает по project_id
        wm.setTask("p9", "новая задача проекта")
        assertEquals("новая задача проекта", wm.get("p9").task)
    }
}
