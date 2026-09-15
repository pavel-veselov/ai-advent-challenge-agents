package com.example.llmagent.agent

import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты долговременной памяти агента: upsert (вставка/обновление), порядок
 * listAll, delete, лимит value 2000, глобальность таблицы между сессиями.
 */
class LongTermMemoryStoreTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun store(): LongTermMemoryStore =
        LongTermMemoryStore(SqliteTestSupport.jdbc(tmpDir.resolve("ltm-${UUID.randomUUID()}.db")))

    @Test
    fun `upsert inserts new entry with all fields`() {
        val f = store()
        val e = f.upsert("s1", "profile", "user_name", "Аня")
        assertTrue(e.id > 0, "вставленная запись получила AUTOINCREMENT id")
        assertEquals("s1", e.sourceSessionId)
        assertEquals("profile", e.type)
        assertEquals("user_name", e.key)
        assertEquals("Аня", e.value)
        assertTrue(e.createdAt.isNotBlank())
        assertTrue(e.updatedAt.isNotBlank())
        assertEquals(1, f.count())
    }

    @Test
    fun `upsert updates same type+key keeping id and created_at`() {
        val f = store()
        val first = f.upsert("s1", "decision", "api_style", "REST")
        // та же пара (type, key) из другой сессии — перезапись, а не вторая строка
        val second = f.upsert("s2", "decision", "api_style", "gRPC")
        assertEquals(first.id, second.id, "id прежний — строка обновлена, а не вставлена")
        assertEquals("s2", second.sourceSessionId, "source_session_id перезаписан")
        assertEquals("gRPC", second.value)
        assertEquals(first.createdAt, second.createdAt, "created_at сохраняется при обновлении")
        assertEquals(1, f.count(), "строка по-прежнему одна")
    }

    @Test
    fun `listAll orders by updated_at desc`() {
        val f = store()
        f.upsert("s1", "knowledge", "fact_old", "старая")
        Thread.sleep(20) // гарантируем различные updated_at (строки сравниваются лексикографически)
        f.upsert("s1", "knowledge", "fact_mid", "средняя")
        Thread.sleep(20)
        f.upsert("s2", "knowledge", "fact_new", "новая")

        val keys = f.listAll().map { it.key }
        assertEquals(listOf("fact_new", "fact_mid", "fact_old"), keys, "свежие записи сверху")
    }

    @Test
    fun `delete returns true for existing and false for missing`() {
        val f = store()
        val e = f.upsert("s1", "profile", "city", "Москва")
        assertTrue(f.delete(e.id), "существующая запись удаляется")
        assertFalse(f.delete(e.id), "повторное удаление той же записи — false")
        assertFalse(f.delete(999999), "несуществующий id — false")
        assertEquals(0, f.count())
    }

    @Test
    fun `value truncated to 2000 chars`() {
        val f = store()
        val longValue = "д".repeat(2500)
        val e = f.upsert("s1", "knowledge", "big_note", longValue)
        assertEquals(2000, e.value.length, "value обрезан до 2000 символов")
        assertEquals(2000, f.listAll().first().value.length, "в БД тоже обрезанное значение")
    }

    @Test
    fun `entries from different sessions coexist in global table`() {
        val f = store()
        f.upsert("s1", "profile", "user_name", "Аня")
        f.upsert("s2", "profile", "city", "Борис")   // другая сессия, свой ключ → отдельная строка
        f.upsert("s1", "decision", "tone", "кратко") // другой type, тот же key → тоже своя строка

        val byKey = f.listAll().associate { it.key to it.value }
        assertEquals("Аня", byKey["user_name"], "запись из s1 живёт рядом с записью s2")
        assertEquals("Борис", byKey["city"], "source_session_id не участвует в уникальности")
        assertEquals(3, f.count(), "таблица глобальная — записи разных сессий сосуществуют")
    }
}
