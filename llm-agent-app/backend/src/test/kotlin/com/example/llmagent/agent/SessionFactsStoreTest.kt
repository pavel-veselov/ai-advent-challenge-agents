package com.example.llmagent.agent

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Юнит-тесты хранилища «липких фактов»: пусто, replaceAll (порядок), замена, очистка. */
class SessionFactsStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): SessionFactsStore =
        SessionFactsStore(SqliteTestSupport.jdbc(tmpDir.resolve("facts-${UUID.randomUUID()}.db")))

    @Test
    fun `GET returns empty map for unknown session`() {
        assertTrue(store().getAll("s1").isEmpty())
    }

    @Test
    fun `replaceAll inserts in map order and getAll preserves it`() {
        val f = store()
        // LinkedHashMap с явным порядком (не лексикографическим) — порядок сохраняется
        val facts = linkedMapOf(
            "имя" to "Аня",
            "цель" to "обучение",
            "предпочтение" to "краткие ответы",
        )
        f.replaceAll("s1", facts)
        assertEquals(listOf("имя", "цель", "предпочтение"), f.getAll("s1").keys.toList())
        assertEquals(facts, f.getAll("s1"))
    }

    @Test
    fun `replaceAll replaces previous facts and order follows new map`() {
        val f = store()
        f.replaceAll("s1", linkedMapOf("a" to "1", "b" to "2"))
        f.replaceAll("s1", linkedMapOf("b" to "два", "c" to "3"))
        val all = f.getAll("s1")
        assertEquals(listOf("b", "c"), all.keys.toList(), "порядок — новой карты, старые ключи удалены")
        assertEquals("два", all["b"])
        assertEquals("3", all["c"])
        assertEquals(2, all.size)
    }

    @Test
    fun `replaceAll with empty map clears facts`() {
        val f = store()
        f.replaceAll("s1", linkedMapOf("a" to "1"))
        f.replaceAll("s1", linkedMapOf())
        assertTrue(f.getAll("s1").isEmpty())
    }

    @Test
    fun `clear removes all facts`() {
        val f = store()
        f.replaceAll("s1", linkedMapOf("a" to "1", "b" to "2"))
        f.clear("s1")
        assertTrue(f.getAll("s1").isEmpty())
        // повторная запись после очистки работает
        f.replaceAll("s1", linkedMapOf("z" to "9"))
        assertEquals(mapOf("z" to "9"), f.getAll("s1"))
    }

    @Test
    fun `remove deletes facts and other session untouched`() {
        val f = store()
        f.replaceAll("s1", linkedMapOf("a" to "1"))
        f.replaceAll("s2", linkedMapOf("x" to "y"))
        f.remove("s1")
        assertTrue(f.getAll("s1").isEmpty())
        assertEquals(mapOf("x" to "y"), f.getAll("s2"))
    }
}
