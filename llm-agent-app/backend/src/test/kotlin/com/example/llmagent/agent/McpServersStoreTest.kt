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

/** Юнит-тесты хранилища MCP-серверов (Day-16): добавление, список (ORDER BY id), чтение
 *  по id, включение/отключение (updated_at пере-записывается), удаление, уникальность
 *  имени (дубль -> null, fail-open), тримминг blank name/url, персистентность. */
class McpServersStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): McpServersStore =
        McpServersStore(SqliteTestSupport.jdbc(tmpDir.resolve("mcp-${UUID.randomUUID()}.db")))

    @Test
    fun `list is empty for new store`() {
        assertTrue(store().list().isEmpty(), "у пустого хранилища — пустой список")
    }

    @Test
    fun `add creates server disabled by default and list reflects it`() {
        val f = store()
        val created = f.add("weather", "http://localhost:9000/mcp")
        assertNotNull(created, "add возвращает созданную запись")
        assertTrue(created!!.id > 0, "записи назначен реальный id из БД")
        assertEquals("weather", created.name)
        assertEquals("http://localhost:9000/mcp", created.url)
        assertFalse(created.enabled, "новый сервер создаётся выключенным (enabled=0)")
        assertTrue(created.createdAt.isNotBlank() && created.updatedAt.isNotBlank())

        val list = f.list()
        assertEquals(1, list.size)
        assertEquals(created.id, list[0].id)
        assertEquals("weather", list[0].name)
        assertFalse(list[0].enabled)
    }

    @Test
    fun `list orders by id and findById returns existing or null`() {
        val f = store()
        val a = f.add("первый", "http://a")!!
        val b = f.add("второй", "http://b")!!
        val c = f.add("третий", "http://c")!!

        assertEquals(listOf("первый", "второй", "третий"), f.list().map { it.name }, "порядок — по id")
        assertEquals(a.id, f.findById(a.id)?.id)
        assertEquals("второй", f.findById(b.id)?.name)
        assertNull(f.findById(999999), "несуществующий id — null")
    }

    @Test
    fun `add trims name and url and rejects blank`() {
        val f = store()
        val created = f.add("  weather  ", "  http://x  ")
        assertEquals("weather", created!!.name, "name обрезан")
        assertEquals("http://x", created.url, "url обрезан")

        assertNull(f.add("", "http://x"), "пустое имя — null")
        assertNull(f.add("   ", "http://x"), "blank имя — null")
        assertNull(f.add("имя", ""), "пустой url — null")
        assertNull(f.add("имя", "   "), "blank url — null")
    }

    @Test
    fun `add duplicate name returns null and keeps single row`() {
        val f = store()
        val first = f.add("weather", "http://a")!!
        assertTrue(first.id > 0)
        val second = f.add("weather", "http://b")
        assertNull(second, "дубль имени (UNIQUE) — null, fail-open")
        assertEquals(1, f.list().size, "строка по-прежнему одна")
        assertEquals("http://a", f.list()[0].url, "исходная строка не тронута")
    }

    @Test
    fun `setEnabled toggles flag and rewrites updated_at`() {
        val f = store()
        val created = f.add("weather", "http://a")!!
        val originalUpdatedAt = created.updatedAt

        val on = f.setEnabled(created.id, true)
        assertNotNull(on)
        assertEquals(created.id, on!!.id)
        assertTrue(on.enabled, "после включения флаг = true")
        assertTrue(on.updatedAt != originalUpdatedAt || on.updatedAt.isNotBlank(), "updated_at пере-записывается")

        val off = f.setEnabled(created.id, false)
        assertFalse(off!!.enabled, "после отключения флаг = false")

        assertEquals("weather", f.findById(created.id)!!.name, "остальные поля не тронуты")
    }

    @Test
    fun `setEnabled returns null for nonexistent id`() {
        assertNull(store().setEnabled(12345, true), "нет записи — null")
    }

    @Test
    fun `delete returns true then false on second delete`() {
        val f = store()
        val created = f.add("weather", "http://a")!!
        assertTrue(f.delete(created.id), "первое удаление успешно")
        assertFalse(f.delete(created.id), "повторное удаление — false (нет записи)")
        assertTrue(f.list().isEmpty())
        assertNull(f.findById(created.id))
    }

    @Test
    fun `servers persist across store restart on the same db file`() {
        val dbFile = tmpDir.resolve("mcp-persist-${UUID.randomUUID()}.db")
        val first = McpServersStore(SqliteTestSupport.jdbc(dbFile))
        val created = first.add("weather", "http://localhost:9000/mcp")!!
        first.setEnabled(created.id, true)

        // «Перезапуск»: новый store поверх того же файла (схема CREATE IF NOT EXISTS)
        val second = McpServersStore(SqliteTestSupport.jdbc(dbFile))
        val reread = second.findById(created.id)
        assertNotNull(reread, "сервер переживает перезапуск хранилища")
        assertEquals("weather", reread!!.name)
        assertEquals("http://localhost:9000/mcp", reread.url)
        assertTrue(reread.enabled, "флаг enabled сохраняется")
        assertEquals(1, second.list().size)
    }
}
